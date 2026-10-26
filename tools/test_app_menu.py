"""Execute the actual ARM64 menu wrappers and verify pinned patch composition."""
import random
import struct
import sys
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'artifacts/tools/python'))
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm64_const import *
from app_menu import TARGETS, patch_app_menu, restore_app_menu, HIDDEN_POSITION
from build_settings_ui import BASELINE


class AppMenuNativeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        with zipfile.ZipFile(BASELINE) as archive:
            cls.original = archive.read('lib/arm64-v8a/libil2cpp.so')
        cls.code = bytes.fromhex(TARGETS[2]['replacement'])
        cls.base = TARGETS[2]['address']

    def test_patch_exact_scope_and_restore(self):
        patched = patch_app_menu(self.original)
        self.assertEqual(restore_app_menu(patched), self.original)
        self.assertEqual(patched[self.base-4:self.base], bytes.fromhex('c0035fd6'))
        changed = {i for i, (a, b) in enumerate(zip(self.original, patched)) if a != b}
        allowed = {i for t in TARGETS for i in range(t['address'], t['address'] + len(bytes.fromhex(t['replacement'])))}
        self.assertLessEqual(changed, allowed)
        damaged = bytearray(patched); damaged[-1] ^= 1
        with self.assertRaises(ValueError): restore_app_menu(damaged)
        with self.assertRaises(ValueError): patch_app_menu(patched)

    def machine(self):
        uc = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
        for address, size in [(self.base & ~4095, 4096), (0x350c000,4096), (0x2cb7000,4096),
                              (0x1b65000,4096), (0x45e1000,4096), (0x45d1000,4096),
                              (0x6000000,0x200000), (0x8000000,0x100000)]:
            uc.mem_map(address, size)
        uc.mem_write(self.base, self.code)
        uc.mem_write(0x45e1330, struct.pack('<Q', 0x6000100))
        uc.mem_write(0x45d1b70, struct.pack('<Q', 0x6000200))
        uc.reg_write(UC_ARM64_REG_SP, 0x8080000)
        return uc

    def run_order(self, ids, positions, initially_hidden=()):
        uc = self.machine(); listing, array, dictionary = 0x6010000,0x6020000,0x6030000
        uc.reg_write(UC_ARM64_REG_X19, listing); uc.reg_write(UC_ARM64_REG_X21, dictionary)
        uc.mem_write(listing+0x10, struct.pack('<Qii',array,len(ids),42))
        uc.mem_write(array+0x18, struct.pack('<Q',len(ids)))
        addresses = {}
        for i, app in enumerate(ids):
            obj, key = 0x6040000+i*0x100, 0x6100000+i*8
            addresses[obj] = app
            uc.mem_write(array+0x20+i*8, struct.pack('<Q',obj))
            uc.mem_write(obj+0x20, struct.pack('<Q',key))
            uc.mem_write(obj+0x53, bytes([app in initially_hidden]))
            uc.mem_write(key, struct.pack('<Q',i))
        calls=[]
        def hook(uc, address, size, _):
            if address == 0x350c7e0: uc.emu_stop(); return
            if address not in (0x2cb7430,0x2cb71a8): return
            self.assertEqual(uc.reg_read(UC_ARM64_REG_X0),dictionary)
            key = uc.reg_read(UC_ARM64_REG_X1)
            index = struct.unpack('<Q',uc.mem_read(key,8))[0]; app=ids[index]
            result = int(app in positions) if address == 0x2cb7430 else positions[app]
            calls.append(address)
            lr = uc.reg_read(UC_ARM64_REG_LR)
            # Managed callees may destroy every caller-saved register.
            for reg in range(UC_ARM64_REG_X0,UC_ARM64_REG_X17+1): uc.reg_write(reg,0xdeadbeef)
            uc.reg_write(UC_ARM64_REG_X0,result & 0xffffffff);uc.reg_write(UC_ARM64_REG_PC,lr)
        uc.hook_add(UC_HOOK_CODE,hook)
        uc.emu_start(self.base,0,count=1000000)
        self.assertEqual(uc.reg_read(UC_ARM64_REG_SP),0x8080000)
        self.assertEqual(struct.unpack('<i',uc.mem_read(listing+0x1c,4))[0],43 if ids else 42)
        result=[addresses[struct.unpack('<Q',uc.mem_read(array+0x20+i*8,8))[0]] for i in range(len(ids))]
        hidden={app for obj,app in addresses.items() if uc.mem_read(obj+0x53,1)[0]}
        priorities={app:struct.unpack('<i',uc.mem_read(obj+0x78,4))[0] for obj,app in addresses.items()}
        return result,hidden,priorities

    def test_order_hiding_and_legacy_negative_positions(self):
        order, hidden, priority = self.run_order(['new','last','hidden','first','legacy','system'],
            {'first':0,'last':20,'hidden':HIDDEN_POSITION,'legacy':-1},['system'])
        self.assertEqual(order,['first','last','new','legacy','hidden','system'])
        self.assertEqual(hidden,{'hidden','system'})
        self.assertEqual(priority['hidden'],2);self.assertEqual(priority['last'],2);self.assertEqual(priority['system'],0)

    def test_sparse_and_duplicate_ranks_are_stable(self):
        order,hidden,_=self.run_order(['a','b','c','d'],{'a':100,'b':4,'c':4,'removed':0})
        self.assertEqual(order,['b','c','a','d']);self.assertFalse(hidden)

    def test_random_orders_with_removed_and_new_packages(self):
        rng=random.Random(7)
        for _ in range(20):
            ids=[f'app{i}' for i in range(100)];rng.shuffle(ids)
            positions={app:rng.choice([-1,0,12,99,1000,HIDDEN_POSITION]) for app in ids[::2]}
            order,hidden,_=self.run_order(ids,positions)
            expected=sorted(ids,key=lambda app:positions.get(app,HIDDEN_POSITION-1) if positions.get(app,-1)>=0 else HIDDEN_POSITION-1)
            self.assertEqual(order,expected)
            self.assertEqual(hidden,{app for app,value in positions.items() if value==HIDDEN_POSITION})

    def test_ar_save_uses_sentinel_only_for_hidden_apps(self):
        for hidden in (False,True):
            uc=self.machine();obj=0x6040000;uc.reg_write(UC_ARM64_REG_X8,obj);uc.mem_write(obj+0x53,bytes([hidden]))
            uc.reg_write(UC_ARM64_REG_LR,0x350c7e0)
            def hook(uc,address,size,_):
                if address==0x350c7e0: uc.emu_stop()
                elif address==0x1b65ec4:
                    uc.reg_write(UC_ARM64_REG_X0,17);uc.reg_write(UC_ARM64_REG_PC,uc.reg_read(UC_ARM64_REG_LR))
            uc.hook_add(UC_HOOK_CODE,hook)
            uc.emu_start(self.base+len(self.code)-20,0,count=50)
            self.assertEqual(uc.reg_read(UC_ARM64_REG_X0),HIDDEN_POSITION if hidden else 17)

    def run_hidden_launcher_creation(self, patched, initial_pages=0):
        """Execute the real Unity iterator until its first menu-show yield."""
        uc = self.machine()
        for address in [0x32f5000, 0x32f0000, 0x32f2000, 0x32e8000, 0x1b64000,
                        0x4902000, 0x48f2000, 0x4546000, 0x45c3000]:
            uc.mem_map(address, 4096)
        library = patch_app_menu(self.original) if patched else self.original
        uc.mem_write(0x32f5c80, library[0x32f5c80:0x32f5f84])
        uc.mem_write(0x49028d7, b'\x01')  # Original method's metadata is initialized.
        uc.mem_write(0x48f29a3, b'\x01')
        uc.mem_write(0x45468f8, struct.pack('<Q', 0x6000500))
        uc.mem_write(0x45c3ea8, struct.pack('<Q', 0x6000100))
        uc.mem_write(0x6000100, struct.pack('<Q', 0x6000200))
        uc.mem_write(0x60002b8, struct.pack('<Q', 0x6000300))
        iterator, manager, infos, items, pages, page = [0x6010000+i*0x10000 for i in range(6)]
        uc.reg_write(UC_ARM64_REG_X0, iterator)
        uc.reg_write(UC_ARM64_REG_LR, 0x350c7e0)
        uc.mem_write(iterator+0x20, struct.pack('<QQ', infos, manager))
        uc.mem_write(manager+0x60, struct.pack('<Q', pages))
        uc.mem_write(manager+0x6c, struct.pack('<I', 7 if initial_pages == 0 else 0))
        uc.mem_write(pages+0x18, struct.pack('<I', initial_pages))
        uc.mem_write(infos+0x18, struct.pack('<I', 280))
        for i in range(280): uc.mem_write(items+i*0x100+0x53, b'\x01')
        created = []
        def hook(uc, address, size, _):
            if address == 0x350c7e0: uc.emu_stop(); return
            if address == 0x1b64fa4:
                uc.reg_write(UC_ARM64_REG_X0, items+uc.reg_read(UC_ARM64_REG_X1)*0x100)
            elif address == 0x32f035c:
                created.append(page)
                uc.mem_write(pages+0x18, struct.pack('<I', 1))
                uc.mem_write(uc.reg_read(UC_ARM64_REG_X1), b'\x01')
                uc.reg_write(UC_ARM64_REG_X0, page)
            elif address == 0x32f23ac or address == 0x32f0094:
                count = struct.unpack('<I', uc.mem_read(pages+0x18, 4))[0]
                self.assertGreater(count, 0, 'stock launcher would index an empty page list')
                self.assertLess(struct.unpack('<I', uc.mem_read(manager+0x6c, 4))[0], count)
            elif address == 0x32e85f0: pass
            elif address == 0x32f02d4: uc.reg_write(UC_ARM64_REG_X0, 0x6000400)
            else: return
            uc.reg_write(UC_ARM64_REG_PC, uc.reg_read(UC_ARM64_REG_LR))
        uc.hook_add(UC_HOOK_CODE, hook)
        uc.emu_start(0x32f5c80, 0, count=30000)
        self.assertEqual(uc.reg_read(UC_ARM64_REG_SP), 0x8080000)
        self.assertEqual(struct.unpack('<I', uc.mem_read(iterator+0x10,4))[0], 2)
        return created

    def test_all_hidden_creates_an_empty_page_before_focus_and_markers(self):
        self.assertEqual(len(self.run_hidden_launcher_creation(True)), 1)

    def test_empty_page_guard_does_not_duplicate_existing_pages(self):
        self.assertEqual(self.run_hidden_launcher_creation(True, initial_pages=1), [])

    def test_original_launcher_cannot_handle_all_apps_hidden(self):
        with self.assertRaisesRegex(AssertionError, 'empty page list'):
            self.run_hidden_launcher_creation(False)


if __name__ == '__main__': unittest.main()

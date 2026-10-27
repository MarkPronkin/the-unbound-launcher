"""Bounded AArch64 selector interpreter for hosts whose Unicorn JIT cannot run."""
from capstone import Cs,CS_ARCH_ARM64,CS_MODE_ARM
from capstone.arm64_const import ARM64_OP_REG,ARM64_OP_IMM


def exercise_selector(test,code,mirror,software,present):
    base=0x12caf6c;cpu=Cs(CS_ARCH_ARM64,CS_MODE_ARM);cpu.detail=True
    # Stop disassembly at RET; the tail contains the two literal ASCII names.
    instructions={i.address:i for i in cpu.disasm(code[:180],base)}
    regs={f'x{i}':0xa000+i for i in range(31)};regs['sp']=0x8001000
    regs.update(x0=0x6000000,x1=0x9000000,x2=0,x19=0x6001000,x30=0x9900000)
    saved={k:v for k,v in regs.items() if k=='sp' or k.startswith('x') and int(k[1:])>=19}
    memory={regs['x19']+(0x40 if mirror else 0x90):0x6002000 if present else 0}
    strings={0x9000000:'FLIP_Y'};keywords=[];calls=[];pc=base+(8 if mirror else 0)

    def read(reg):
        name=cpu.reg_name(reg)
        if name in ('xzr','wzr'):return 0
        if name=='lr':name='x30'
        if name=='fp':name='x29'
        return regs['x'+name[1:]]&0xffffffff if name.startswith('w') else regs[name]
    def write(reg,value):
        name=cpu.reg_name(reg)
        if name in ('xzr','wzr'):return
        if name=='lr':name='x30'
        if name=='fp':name='x29'
        if name.startswith('w'):name='x'+name[1:];value&=0xffffffff
        regs[name]=value&0xffffffffffffffff
    def value(op):return read(op.reg) if op.type==ARM64_OP_REG else op.imm
    def invoke(target):
        calls.append(target);result=0
        if target==0x347ec8c:
            test.assertEqual(regs['x0'],0x6000000);test.assertEqual(regs['x2'],0)
            keywords.append(strings[regs['x1']])
        elif target==0xd601e0:
            slot=regs['x0'];test.assertIn(slot,(0x4808518,0x48091c0));memory[slot]=slot+0x10000000
        elif target==0x1860fb8:
            test.assertEqual(regs['x0'],0x6002000);test.assertEqual(regs['x1'],0);result=0x6003000
        elif target==0xe04d30:
            at=regs['x0']-base;test.assertGreaterEqual(at,180)
            name=code[at:code.index(b'\0',at)].decode();result=0x9000100+len(strings)*0x100;strings[result]=name
        elif target==0x29bee44:
            test.assertEqual(regs['x0'],0x148091c0);result=0x6004000
        elif target==0x2c10dd0:
            test.assertEqual(regs['x0'],0x6003000);test.assertEqual(strings[regs['x1']],'unboundNeedsSoftwareDecode')
            test.assertEqual(regs['x2'],0x6004000);test.assertEqual(regs['x3'],0x14808518);result=int(software)
        else:raise AssertionError(f'Unexpected engine call {target:#x}')
        # Exercise preservation across ABI-conforming caller-saved clobbers.
        for i in range(19):regs[f'x{i}']=0xb000+i
        regs['x0']=result

    for step in range(100):
        if pc==0x9900000:break
        i=instructions[pc];ops=i.operands;next_pc=pc+4
        if i.mnemonic in ('adr','adrp','mov'):write(ops[0].reg,value(ops[1]))
        elif i.mnemonic=='add':write(ops[0].reg,value(ops[1])+value(ops[2]))
        elif i.mnemonic in ('b','bl'):
            if i.mnemonic=='bl':regs['x30']=next_pc;invoke(ops[0].imm)
            else:next_pc=ops[0].imm
        elif i.mnemonic=='cbz':
            if read(ops[0].reg)==0:next_pc=ops[1].imm
        elif i.mnemonic in ('ldr','str','ldp','stp'):
            pair=i.mnemonic in ('ldp','stp');mem_index=2 if pair else 1;mem=ops[mem_index].mem
            addr=read(mem.base)+mem.disp
            for n in range(2 if pair else 1):
                if i.mnemonic in ('ldr','ldp'):write(ops[n].reg,memory[addr+n*8])
                else:memory[addr+n*8]=read(ops[n].reg)
            if i.writeback:
                post=ops[mem_index+1].imm if len(ops)>mem_index+1 else mem.disp
                write(mem.base,read(mem.base)+post)
        elif i.mnemonic=='ret':next_pc=regs['x30']
        else:raise AssertionError(f'Unsupported selector instruction {i.mnemonic} {i.op_str}')
        pc=next_pc
    else:raise AssertionError('Selector failed to return')
    test.assertEqual(keywords,['FLIP_Y']+(['GAMMA_TO_LINEAR'] if present and software else []))
    for name,expected in saved.items():test.assertEqual(regs[name],expected,name)
    test.assertEqual(0x2c10dd0 in calls,present)


def exercise_oes_selector(test,code,kind,software,present):
    """kind is app, mirror, or change. Direct OES sets _DecodeGamma only for software decode."""
    base=0x12cb04c;cpu=Cs(CS_ARCH_ARM64,CS_MODE_ARM);cpu.detail=True
    instructions={i.address:i for i in cpu.disasm(code,base)}
    entry={'app':0,'mirror':8,'change':16}[kind]
    regs={f'x{i}':0xa000+i for i in range(31)};regs['sp']=0x8001000;regs['s0']=0.0
    view,texture,surface,material=0x6005000,0x6007000,0x6002000,0x6006000
    owner=0x6001000
    regs.update(x0=view,x1=texture,x2=1,x19=owner,x20=owner,x30=0x9900000)
    saved={k:v for k,v in regs.items() if k=='sp' or k.startswith('x') and int(k[1:])>=19}
    offset={'app':0x90,'mirror':0x40,'change':0x90}[kind]
    memory={owner+offset:surface if present else 0, view+0x178:material}
    strings={};calls=[];floats=[];pc=base+entry
    engine={0x34dcdac,0xd601e0,0x1860fb8,0xe04d30,0x29bee44,0x2c10dd0,0x347d844}

    def read(reg):
        name=cpu.reg_name(reg)
        if name in ('xzr','wzr'):return 0
        if name=='lr':name='x30'
        if name=='fp':name='x29'
        return regs['x'+name[1:]]&0xffffffff if name.startswith('w') else regs[name]
    def write(reg,value):
        name=cpu.reg_name(reg)
        if name in ('xzr','wzr'):return
        if name=='lr':name='x30'
        if name=='fp':name='x29'
        if name.startswith('w'):name='x'+name[1:];value&=0xffffffff
        if name.startswith('s'):regs[name]=value;return
        regs[name]=value&0xffffffffffffffff
    def value(op):return read(op.reg) if op.type==ARM64_OP_REG else op.imm
    def invoke(target):
        calls.append(target);result=0
        if target==0x34dcdac:
            test.assertEqual(regs['x0'],view);test.assertEqual(regs['x1'],texture);test.assertEqual(regs['x2']&0xffffffff,1)
        elif target==0xd601e0:
            slot=regs['x0'];test.assertIn(slot,(0x4808518,0x48091c0));memory[slot]=slot+0x10000000
        elif target==0x1860fb8:
            test.assertEqual(regs['x0'],surface);test.assertEqual(regs['x1'],0);result=0x6003000
        elif target==0xe04d30:
            at=regs['x0']-base;name=code[at:code.index(b'\0',at)].decode();result=0x9000100+len(strings)*0x100;strings[result]=name
        elif target==0x29bee44:
            test.assertEqual(regs['x0'],0x148091c0);result=0x6004000
        elif target==0x2c10dd0:
            test.assertEqual(regs['x0'],0x6003000);test.assertEqual(strings[regs['x1']],'unboundNeedsSoftwareDecode')
            test.assertEqual(regs['x2'],0x6004000);test.assertEqual(regs['x3'],0x14808518);result=int(software)
        elif target==0x347d844:
            test.assertEqual(regs['x0'],material);test.assertEqual(strings[regs['x1']],'_DecodeGamma')
            test.assertEqual(regs['x2'],0);test.assertEqual(regs['s0'],1.0);floats.append(regs['s0'])
        else:raise AssertionError(f'Unexpected engine call {target:#x}')
        for i in range(19):regs[f'x{i}']=0xb000+i
        regs['x0']=result

    for step in range(160):
        if pc in (0x9900000,0x34ddd6c):break
        i=instructions[pc];ops=i.operands;next_pc=pc+4
        if i.mnemonic=='fmov':
            regs['s0']=float(ops[1].fp)
        elif i.mnemonic in ('adr','adrp','mov'):write(ops[0].reg,value(ops[1]))
        elif i.mnemonic=='add':write(ops[0].reg,value(ops[1])+value(ops[2]))
        elif i.mnemonic in ('b','bl'):
            if i.mnemonic=='bl' and ops[0].imm in engine:
                regs['x30']=next_pc;invoke(ops[0].imm)
            elif i.mnemonic=='bl':
                regs['x30']=next_pc;next_pc=ops[0].imm
            else:next_pc=ops[0].imm
        elif i.mnemonic=='cbz':
            if read(ops[0].reg)==0:next_pc=ops[1].imm
        elif i.mnemonic in ('ldr','str','ldp','stp'):
            pair=i.mnemonic in ('ldp','stp');mem_index=2 if pair else 1;mem=ops[mem_index].mem
            pre=i.writeback and mem.index==0 and not (len(ops)>mem_index+1)
            if pre:write(mem.base,read(mem.base)+mem.disp);addr=read(mem.base)
            else:addr=read(mem.base)+mem.disp
            for n in range(2 if pair else 1):
                if i.mnemonic in ('ldr','ldp'):write(ops[n].reg,memory.get(addr+n*8,0))
                else:memory[addr+n*8]=read(ops[n].reg)
            if i.writeback and not pre:
                post=ops[mem_index+1].imm if len(ops)>mem_index+1 else mem.disp
                write(mem.base,read(mem.base)+post)
        elif i.mnemonic=='ret':next_pc=regs['x30']
        else:raise AssertionError(f'Unsupported OES selector instruction {i.mnemonic} {i.op_str}')
        pc=next_pc
    else:raise AssertionError('OES selector failed to return')
    if kind=='change':test.assertEqual(pc,0x34ddd6c)
    else:
        test.assertEqual(pc,0x9900000)
        for name,expected in saved.items():test.assertEqual(regs[name],expected,name)
    test.assertIn(0x34dcdac,calls)
    test.assertEqual(floats,[1.0] if present and software else [])
    test.assertEqual(0x2c10dd0 in calls,present)

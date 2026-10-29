"""Run the shipped Unity native GPU-program reader, including post-GLSL metadata.

UnityPy exports the source array but stops before the required flags/parameters.
The engine's own bounded parser catches truncation which GLSL compilation misses.
Only keyword bookkeeping, string allocation and memcpy are host fixtures; every
binary bounds check and all program-data reads execute from the pinned engine.
"""
import hashlib
from pathlib import Path
import struct
import sys

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'artifacts/tools/python'))
from unicorn import Uc, UC_ARCH_ARM64, UC_MODE_ARM, UC_HOOK_CODE
from unicorn.arm64_const import UC_ARM64_REG_X0, UC_ARM64_REG_X1, UC_ARM64_REG_X2, UC_ARM64_REG_X3, UC_ARM64_REG_X4, UC_ARM64_REG_X30, UC_ARM64_REG_SP, UC_ARM64_REG_PC, UC_ARM64_REG_TPIDR_EL0

ENGINE='lib/arm64-v8a/libunity.so'
ENGINE_SHA256='fb1c64701fb10d934a9b36ded88086790d8ad2e5b92825736939020a958093d5'
READER=0x291580
CURSOR=0x2000000
DATA=0x2001000
OBJECT=0x2100000
HEAP=0x2200000
STACK=0x2300000
TLS=0x2400000
STOP=0x2500000


class UnityProgramReader:
    def __init__(self,engine):
        if hashlib.sha256(engine).hexdigest()!=ENGINE_SHA256:
            raise ValueError('Unexpected Unity engine for native shader-loader validation')
        self.cpu=Uc(UC_ARCH_ARM64,UC_MODE_ARM)
        self.cpu.mem_map(0,(len(engine)+0xfff)&~0xfff);self.cpu.mem_write(0,engine)
        for address,size in ((CURSOR,0x100000),(OBJECT,0x10000),(HEAP,0x10000),(STACK,0x10000),(TLS,0x1000)):
            self.cpu.mem_map(address,size)
        self.cpu.hook_add(UC_HOOK_CODE,self._call)

    def _read(self,address):return struct.unpack('<Q',self.cpu.mem_read(address,8))[0]
    def _write(self,address,value):self.cpu.mem_write(address,struct.pack('<Q',value))

    def _call(self,cpu,address,size,data):
        # These are engine services outside the serialized-data reader, not GL.
        if address in (0x278524,0x294788,0x293770,0x293ba8):
            cpu.reg_write(UC_ARM64_REG_X0,0)
        elif address==0x291610:
            cursor=cpu.reg_read(UC_ARM64_REG_X0);end=cpu.reg_read(UC_ARM64_REG_X1)
            at=self._read(cursor)
            if at+4>end:result=0
            else:
                length=struct.unpack('<I',cpu.mem_read(at,4))[0]
                next_at=(at+4+length+3)&~3
                result=int(next_at<=end)
                if result:self._write(cursor,next_at)
            cpu.reg_write(UC_ARM64_REG_X0,result)
        elif address==0x1250f4:
            target=cpu.reg_read(UC_ARM64_REG_X0);length=cpu.reg_read(UC_ARM64_REG_X1)
            if length>0x10000:raise ValueError('Unexpected shader source allocation')
            self._write(target,HEAP);self._write(target+0x18,length*2)
        elif address==0xb8a50:
            target=cpu.reg_read(UC_ARM64_REG_X0);source=cpu.reg_read(UC_ARM64_REG_X1);length=cpu.reg_read(UC_ARM64_REG_X2)
            cpu.mem_write(target,bytes(cpu.mem_read(source,length)))
        elif address in (0x167828,0x26950c):
            raise ValueError('Unexpected non-empty native shader parameter allocation')
        else:return
        cpu.reg_write(UC_ARM64_REG_PC,cpu.reg_read(UC_ARM64_REG_X30))

    def accepts(self,record):
        if len(record)>0xf0000:raise ValueError('Shader record is unexpectedly large')
        cpu=self.cpu
        cpu.mem_write(OBJECT,bytes(0x10000));cpu.mem_write(HEAP,bytes(0x10000))
        cpu.mem_write(STACK,bytes(0x10000));cpu.mem_write(DATA,record)
        self._write(CURSOR,DATA)
        for reg,value in ((UC_ARM64_REG_X0,CURSOR),(UC_ARM64_REG_X1,DATA+len(record)),
                          (UC_ARM64_REG_X2,OBJECT+0x1000),(UC_ARM64_REG_X3,OBJECT),
                          (UC_ARM64_REG_X4,OBJECT+0x2000),(UC_ARM64_REG_X30,STOP),
                          (UC_ARM64_REG_SP,STACK+0xf000),(UC_ARM64_REG_TPIDR_EL0,TLS)):
            cpu.reg_write(reg,value)
        cpu.emu_start(READER,STOP,count=10000)
        if cpu.reg_read(UC_ARM64_REG_PC)!=STOP:raise ValueError('Unity shader reader did not return')
        return bool(cpu.reg_read(UC_ARM64_REG_X0))


def validate_shader_programs(asset,reader):
    import UnityPy
    from UnityPy.helpers import CompressionHelper
    obj=UnityPy.load(asset).objects[0];tree=obj.read_typetree()
    if tree['platforms']!=[9] or tree['offsets']!=[[0]]:raise ValueError('Unexpected app shader platform layout')
    blob=CompressionHelper.decompress_lz4(bytes(tree['compressedBlob']),tree['decompressedLengths'][0][0])
    count=struct.unpack_from('<I',blob)[0];checked=0
    for index in range(count):
        offset,length,segment=struct.unpack_from('<III',blob,4+index*12)
        if segment or offset+length>len(blob):raise ValueError('Invalid app shader record extent')
        record=blob[offset:offset+length]
        if len(record)<8:raise ValueError('Truncated app shader header')
        if struct.unpack_from('<I',record,4)[0]!=4:continue
        if not reader.accepts(record):raise ValueError(f'Unity native loader rejects shader record {index}')
        checked+=1
    return checked

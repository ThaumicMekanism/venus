package venus.riscv.insts.floating

import venus.riscv.insts.dsl.floating.FRRTypeInstruction

val fcvtsd = FRRTypeInstruction(
        name = "fcvt.s.d",
        opcode = 0b1010011,
        funct7 = 0b0100000,
        rs2 = 0b00001,
        eval32 = { a, b -> Decimal(f = a.getCurrentDouble().toFloat()) }
)
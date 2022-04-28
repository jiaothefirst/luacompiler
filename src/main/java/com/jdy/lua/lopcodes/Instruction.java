package com.jdy.lua.lopcodes;

import static com.jdy.lua.lopcodes.Instructions.create_sJ;
import static com.jdy.lua.lopcodes.Instructions.getArgsJ;

/**
 * 定义指令
 */

public class Instruction {
    int ins;
    public int getIns() {
        return ins;
    }
    public void setIns(int i){
        this.ins = i;
    }
    public Instruction(){

    }
    public Instruction(int i){
        this.ins = i;
    }





    public static void main(String[] args) {

        Instruction instruction = create_sJ(OpCode.OP_ADD.getCode(),-1,0);
        System.out.println(getArgsJ(instruction));

    }















}
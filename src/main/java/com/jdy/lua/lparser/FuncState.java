package com.jdy.lua.lparser;

import com.jdy.lua.lex.LexState;
import com.jdy.lua.lobjects.Proto;
import com.jdy.lua.lopcodes.Instruction;
import lombok.Data;

@Data
public class FuncState {
    Proto proto;
    FuncState prev;
    LexState lexState;
    BlockCnt blockCnt;
    int pc;
    int lasttarget;
    int previousline;
    int numOfConstants;
    int np;
    /**
     * FuncState中第一个 local var在Dyndata中的下标
     */
    int firstlocal;
    /**
     * FuncState中第一个label 在 DynData中的下标
     */
    int firstlabel;
    int ndebugvars;
    int nactvar;
    /** upVal的数量 */
    int nups;
    /*当前函数栈的下一个可用位置*/
    int freereg;
    int iwthabs;
    /**function needs to close upvalues when returning */
    boolean needclose;

    /**
     * 新增指令
     */
    public void addInstruction(Instruction i){
        proto.getCode().add(i);
        pc++;
    }
    /**
     * 移除上一个指令
     */
    public void removeLastInstruciton(){
        proto.getCode().remove(proto.getCode().size() - 1);
        pc--;
    }

    /**
     * free reg --
     */
    public void decreFreeReg(){
        if(freereg == 0){
            return;
        }
        freereg--;
    }
    /**
     * free reg ++
     */
    public void incrFreeReg(int n){
        freereg+=n;
    }

}

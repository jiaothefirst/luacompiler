package com.jdy.lua.lparser2.expr;

import com.jdy.lua.lcodes2.GenerateInfo;
import com.jdy.lua.lcodes2.InstructionGenerator;

import java.util.ArrayList;
import java.util.List;

public class FuncArgs extends Expr{
    private List<Expr> expr1 = new ArrayList<>();
    private StringExpr stringExpr;
    private TableConstructor constructor;

    public FuncArgs(StringExpr stringExpr) {
        this.stringExpr = stringExpr;
    }

    public FuncArgs(TableConstructor constructor) {
        this.constructor = constructor;
    }
    public FuncArgs(){

    }

    public void addExprList(ExprList e){
        expr1.addAll(e.getExprList());
    }
    @Override
    public GenerateInfo generate(InstructionGenerator generator) {
       return  generator.generate(this);
    }
    @Override
    public GenerateInfo generate(InstructionGenerator generator, GenerateInfo info) {
        return generator.generate(this,info);
    }

}

package com.jdy.lua.lparser2.expr;

import com.jdy.lua.lcodes.BinOpr;
import com.jdy.lua.lcodes.UnOpr;
import com.jdy.lua.lcodes2.ExprDesc;
import com.jdy.lua.lcodes2.InstructionGenerator;
import lombok.Data;

@Data
public class SubExpr extends Expr{

    private UnOpr unOpr;
    private Expr subExpr1;
    private BinOpr binOpr = null;
    private SubExpr subExpr2;

    public SubExpr(Expr subExpr1) {
        this.subExpr1 = subExpr1;
    }

    public SubExpr(UnOpr unOpr, SubExpr subExpr) {
        this.unOpr = unOpr;
        this.subExpr1 = subExpr;
    }
    @Override
    public void generate(InstructionGenerator generator, ExprDesc exprDesc) {
        generator.generate(this,exprDesc);
    }

}

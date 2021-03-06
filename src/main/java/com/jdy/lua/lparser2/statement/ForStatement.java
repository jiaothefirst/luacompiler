package com.jdy.lua.lparser2.statement;

import com.jdy.lua.lcodes2.InstructionGenerator;
import com.jdy.lua.lparser2.expr.Expr;
import com.jdy.lua.lparser2.expr.NameExpr;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class ForStatement extends Statement{
    boolean isGeneric;

    private NameExpr name1;
    private List<NameExpr> nameExprList;
    private Expr expr1;
    private Expr expr2;
    private Expr expr3;
    private List<Expr> exprList;

    private BlockStatement block;

    public ForStatement(NameExpr name1, Expr expr1, Expr expr2, Expr expr3,BlockStatement block) {
        this.name1 = name1;
        this.expr1 = expr1;
        this.expr2 = expr2;
        this.expr3 = expr3;
        this.block = block;
        isGeneric = false;
    }
    public ForStatement(List<NameExpr> nameExprList,List<Expr> exprList,BlockStatement block){
        this.nameExprList = nameExprList;
        this.exprList = exprList;
        this.block = block;
        isGeneric = true;

    }

    @Override
    public void generate(InstructionGenerator ins) {
        ins.generate(this);
    }
}

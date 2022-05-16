package com.jdy.lua.lparser2;

import com.jdy.lua.lcodes.BinOpr;
import com.jdy.lua.lcodes.UnOpr;
import com.jdy.lua.lex.LexState;
import com.jdy.lua.lex.TokenEnum;
import com.jdy.lua.lparser2.expr.*;
import com.jdy.lua.lparser2.statement.*;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static com.jdy.lua.lcodes.BinOpr.getBinopr;
import static com.jdy.lua.lex.Lex.*;
import static com.jdy.lua.lex.TokenEnum.*;

public class LParser {
    public static boolean blockFollow(LexState ls,boolean withUntil){
        switch (ls.getCurTokenEnum()){
            case ELSE: case ELSEIF:
            case END: case EOF:
                return true;
            case UNTIL:
                return withUntil;
            default:
                return false;

        }
    }
    public static BlockStatement block(LexState ls){
        StatList statList = new StatList();
        while(!blockFollow(ls,true)){
            statList.addStatement(statement(ls));
        }
        return new BlockStatement(statList);
    }
    public static Statement statement(LexState ls){
        int line = ls.getLinenumber();
        switch (ls.getCurTokenEnum()){

            case IF: {
                luaX_Next(ls);
                return ifStat(ls, line);

            }
            case WHILE: {
                luaX_Next(ls);
                return whileStat(ls, line);

            }
            case DO: {
                luaX_Next(ls);  /* skip DO */
                Statement s = block(ls);
                checkMatch(ls, END, DO, line);
                return s;

            }
            case FOR: {
                return forStat(ls, line);

            }
            case REPEAT: {
                return repeatStat(ls, line);

            }
            case FUNCTION: {
                return funcStat(ls, line);

            }
            case LOCAL: {
                luaX_Next(ls);
                if (testNext(ls, FUNCTION))
                    return localFunc(ls,line);
                else
                    return localStat(ls,line);

            }
            case DOU_COLON: {
                luaX_Next(ls);
                return labelStat(ls, line);

            }
            case RETURN: {
                luaX_Next(ls);
                return retStat(ls,line);

            }
            case BREAK: {
                luaX_Next(ls);
                return breakStat(ls,line);

            }
            case GOTO: {
                luaX_Next(ls);
                return gotoStat(ls,line);

            }
            case SEMICON:{
                luaX_Next(ls);
                return null;
            }
            default:
                return exprStat(ls,line);
        }
    }

    public static IfStatement ifStat(LexState ls,int line){
        IfStatement ifStatement;
        Expr cond = expr(ls);
        checkNext(ls,THEN);
        BlockStatement block = block(ls);
        ifStatement = new IfStatement(cond,block);
        while(ls.getCurTokenEnum() == ELSEIF){
            checkNext(ls,ELSEIF);
            ifStatement.addElseThenCond(expr(ls));
            checkNext(ls,THEN);
            ifStatement.addElseThenBlock(block(ls));
        }
        if(testNext(ls,ELSE)){
            ifStatement.setElseBlock(block(ls));
        }
        checkMatch(ls,END,IF,line);
        return ifStatement;
    }
    public static WhileStatement whileStat(LexState ls, int line){
        Expr cond = expr(ls);
        checkNext(ls,DO);
        BlockStatement block = block(ls);
        checkMatch(ls,END,WHILE,line);
        return new WhileStatement(cond,block);
    }

    public static ForStatement forStat(LexState ls, int line){
        luaX_Next(ls);
        NameExpr nameExpr = new NameExpr(ls.getCurrTk().getS());
        luaX_Next(ls);
        switch (ls.getCurTokenEnum()){
            //数值型for循环
            case ASSIGN:
                luaX_Next(ls);
                Expr expr1 = expr(ls);
                checkNext(ls,COMMA);
                Expr expr2 = expr(ls);
                Expr expr3 = null;
                if(testNext(ls,COMMA)){
                     expr3 = expr(ls);
                }
                checkNext(ls,DO);
                BlockStatement block = block(ls);
                checkMatch(ls,END,FOR,line);
                return new ForStatement(nameExpr,expr1,expr2,expr3,block);

            case COMMA:
            case IN:
                List<NameExpr> nameExprList = new ArrayList<>();
                nameExprList.add(nameExpr);
                while(testNext(ls,COMMA)){
                    nameExprList.add(new NameExpr(ls.getCurrTk().getS()));
                    luaX_Next(ls);
                }
                checkNext(ls,IN);
                List<Expr> exprList = exprList(ls);
                checkNext(ls,DO);
                BlockStatement block2 = block(ls);
                checkMatch(ls,END,FOR,line);
                return new ForStatement(nameExprList,exprList,block2);
            default:
                System.err.println("语法错误");
        }
        return null;
    }
    public static RepeatStatement repeatStat(LexState ls,int line){
        luaX_Next(ls);
        BlockStatement block = block(ls);
        checkMatch(ls,UNTIL,REPEAT,line);
        Expr cond = expr(ls);

        return new RepeatStatement(block,cond);
    }
    public static FunctionStat funcStat(LexState ls, int line){
        luaX_Next(ls);


        List<String> exprs = new ArrayList<>();
        // nameExpr总是存储第一个变量
        NameExpr nameExpr = new NameExpr(ls.getCurrTk().getS());
        exprs.add(ls.getCurrTk().getS());
        luaX_Next(ls);
        boolean isMethod = false;
        while(ls.getCurTokenEnum() == DOT){
            luaX_Next(ls);
            exprs.add(ls.getCurrTk().getS());
            luaX_Next(ls);
        }
        if(ls.getCurTokenEnum() == COLON){
            luaX_Next(ls);
            exprs.add(ls.getCurrTk().getS());
            isMethod = true;
            luaX_Next(ls);
        }
        checkNext(ls,SMALL_LEFT);
        ParList parList = parList(ls);
        BlockStatement blockStatement = block(ls);
        checkNext(ls,END);
        FunctionStat fs =  new FunctionStat(nameExpr,blockStatement,parList);
        exprs.remove(0);
        fs.setFieldDesc(exprs.stream().map(StringExpr::new).collect(Collectors.toList()));
        fs.setMethod(isMethod);
        return fs;
    }
    public static LocalStatement localStat(LexState ls,int line){
        LocalStatement localStatement = new LocalStatement();
        int index = 0;
        do{
            localStatement.addVarName(ls.getCurrTk().getS());
            luaX_Next(ls);

            if(testNext(ls,LT)){
                String attr = ls.getCurrTk().getS();
                checkNext(ls,GT);
                if("const".equals(attr)){
                    localStatement.addNameExprAttributes(index,attr);
                } else if("close".equals(attr)){
                    localStatement.addNameExprAttributes(index,attr);
                } else{
                    System.err.println("错误的变量属性");
                }
            }

            index++;
        }while(testNext(ls,COMMA));
        if(testNext(ls,ASSIGN)){
            localStatement.setExprList(exprList(ls));
        }
        return localStatement;
    }

    public static ParList parList(LexState ls){
        List<NameExpr> nameExprs = new ArrayList<>();
        ParList parList = new ParList();
        parList.setNameExprs(nameExprs);
        if(ls.getCurTokenEnum() == SMALL_RIGHT){
            luaX_Next(ls);
            return parList;
        }
        boolean hasVararg = false;
        do{
            if(ls.getCurTokenEnum() == NAME){
                nameExprs.add(new NameExpr(ls.getCurrTk().getS()));
            } else if(ls.getCurTokenEnum() == VARARG){
                hasVararg = true;
            }
            luaX_Next(ls);
        }while(ls.getCurTokenEnum() != SMALL_RIGHT);
        parList.setHasVararg(hasVararg);
        luaX_Next(ls);
        return parList;
    }
    public static LocalFuncStat localFunc(LexState ls,int line){
       String name = ls.getCurrTk().getS();
       checkNext(ls,SMALL_LEFT);
       ParList parList = parList(ls);
       BlockStatement blockStatement = block(ls);
       checkNext(ls,END);
       return new  LocalFuncStat(name,parList,blockStatement);
    }
    public static LabelStatement labelStat(LexState ls,int line){
        checkNext(ls,DOU_COLON);
        LabelStatement labelStatement = new LabelStatement(new NameExpr(ls.getCurrTk().getS()));
        checkNext(ls,DOU_COLON);
        return labelStatement;
    }
    public static ReturnStatement retStat(LexState ls,int line){
        List<Expr> exprList = exprList(ls);
        //无返回值
        if(exprList.size() ==1 && exprList.get(0) == null){
            return new ReturnStatement();
        }
        return new ReturnStatement(exprList);
    }
    public static BreakStatement breakStat(LexState ls,int line){
        return new BreakStatement();
    }
    public static GotoStatement gotoStat(LexState ls,int line){
        GotoStatement gotoStatement = new GotoStatement(ls.getCurrTk().getS());
        luaX_Next(ls);
        return gotoStatement;
    }
    public static ExprStatement exprStat(LexState ls,int line){
        Expr s = suffixedExp(ls);
        ExprStatement state = new ExprStatement();
        if(ls.getCurTokenEnum() == ASSIGN || ls.getCurTokenEnum() == COMMA){
            state.addLeft(s);
            while(testNext(ls,COMMA)){
                state.addLeft(suffixedExp(ls));
            }
            //读取到了 =
            checkNext(ls,ASSIGN);
            state.setRights(exprList(ls));
        } else{
            //函数调用
            state.setFunc(s);
        }
        return state;
    }
    public static List<Expr> exprList(LexState ls){
        List<Expr> exprList = new ArrayList<>();
        exprList.add(expr(ls));
        while(testNext(ls,COMMA)){
            exprList.add(expr(ls));
        }
        return exprList;
    }
    public static Expr expr(LexState ls){
        return logicOrExpr(ls);
    }



    public static Expr logicOrExpr(LexState ls){
        Expr expr = logicAndExpr(ls);
        while(ls.getCurTokenEnum() == OR){
            luaX_Next(ls);
            expr = new LogicExpr(expr,logicAndExpr(ls), BinOpr.OPR_OR);
        }
        return expr;
    }
    public static Expr logicAndExpr(LexState ls){
        Expr expr = relExpr(ls);
        while(ls.getCurTokenEnum() == AND){
            luaX_Next(ls);
            expr = new LogicExpr(expr,relExpr(ls), BinOpr.OPR_AND);
        }
        return expr;
    }
    private static boolean isRelation(TokenEnum tokenEnum){
        return tokenEnum == EQ || tokenEnum == LE || tokenEnum == GT || tokenEnum == GE || tokenEnum == NE || tokenEnum == LT;
    }
    public static Expr relExpr(LexState ls){
       Expr expr = pow(ls);
       while(isRelation(ls.getCurTokenEnum())){
           BinOpr op = getBinopr(ls.getCurTokenEnum());
           luaX_Next(ls);
           expr = new RelationExpr(expr,pow(ls),op);
       }
       return expr;
    }
    public static Expr pow(LexState ls){
        Expr expr = shift(ls);
        while(ls.getCurTokenEnum() == POW){
            luaX_Next(ls);
            expr = new BinaryExpr(expr,shift(ls),BinOpr.OPR_POW);
        }
        return expr;
    }
    public static Expr shift(LexState ls){
        Expr expr = bitOr(ls);
        while(ls.getCurTokenEnum() == LSHIFT || ls.getCurTokenEnum() == RSHIFT){
            BinOpr opr =  getBinopr(ls.getCurTokenEnum());
            luaX_Next(ls);
            expr = new BinaryExpr(expr,bitOr(ls),opr);
        }
        return expr;
    }
    public static Expr bitOr(LexState ls){
        Expr expr = bitAnd(ls);
        while(ls.getCurTokenEnum() == BITOR){
            luaX_Next(ls);
            expr = new BinaryExpr(expr,bitAnd(ls),BinOpr.OPR_BOR);
        }
        return expr;

    }

    public static Expr bitAnd(LexState ls){
        Expr expr = addSub(ls);
        while(ls.getCurTokenEnum() == BITAND){
            luaX_Next(ls);
            expr = new BinaryExpr(expr,addSub(ls),BinOpr.OPR_BAND);
        }
        return expr;
    }

    public static Expr addSub(LexState ls){
        Expr expr = mulDivMod(ls);
        while(ls.getCurTokenEnum() == ADD || ls.getCurTokenEnum() == SUB){
            BinOpr opr =  getBinopr(ls.getCurTokenEnum());
            luaX_Next(ls);
            expr = new BinaryExpr(expr,mulDivMod(ls),opr);
        }
        return expr;

    }
    public static Expr mulDivMod(LexState ls){
        Expr expr = unary(ls);
        while(ls.getCurTokenEnum() == MUL || ls.getCurTokenEnum() == DIV || ls.getCurTokenEnum() == IDIV || ls.getCurTokenEnum() == MOD){
            BinOpr opr =  getBinopr(ls.getCurTokenEnum());
            luaX_Next(ls);
            expr = new BinaryExpr(expr,unary(ls),opr);
        }
        return expr;
    }

    public static Expr unary(LexState ls){
        UnOpr op = UnOpr.getUnopr(ls.getCurTokenEnum());
        if(op != UnOpr.OPR_NOUNOPR){
            luaX_Next(ls);
            if(op == UnOpr.OPR_NOT){
                return new NotExpr(simpleExp(ls));
            } else{
                return new UnaryExpr(op,simpleExp(ls));
            }
        }
        return simpleExp(ls);
    }

    public static Expr  simpleExp(LexState ls){
         /* simpleexp -> FLT | INT | STRING | NIL | TRUE | FALSE | ... |
                  constructor | FUNCTION body | suffixedexp */
        Expr expr;
        switch (ls.getCurTokenEnum()){
            case BIG_LEFT:
                expr = constructor(ls);
                break;
            case FLOAT:
                expr = new FloatExpr(ls.getCurrTk().getR());
                luaX_Next(ls);
                break;
            case INT:
                expr = new IntExpr(ls.getCurrTk().getI());
                luaX_Next(ls);
                break;
            case STRING: {
                expr = new StringExpr(ls.getCurrTk().getS());
                luaX_Next(ls);
                break;
            }
            case NIL: {
                expr = new NilExpr();
                luaX_Next(ls);
                break;
            }
            case TRUE: {
                expr = new TrueExpr();
                luaX_Next(ls);
                break;
            }
            case FALSE: {
                expr = new FalseExpr();
                luaX_Next(ls);
                break;
            }
            case VARARG: {
                expr = new VarargExpr();
                luaX_Next(ls);
                break;
            }

            case FUNCTION: {
                 luaX_Next(ls);
                 checkNext(ls,SMALL_LEFT);
                 ParList parList = parList(ls);
                 BlockStatement st = block(ls);
                 luaX_Next(ls);
                 expr = new FunctionBody(st,parList);
                 break;
            }
            default: {
                expr =suffixedExp(ls);
                break;
            }

        }
        return expr;
    }

    /**
     *  primaryexp -> NAME | '(' expr ')'
     */
    public static Expr primaryExpr(LexState ls){
        Expr expr;
        if(ls.getCurTokenEnum()== SMALL_LEFT){
            luaX_Next(ls);
            expr = expr(ls);
            checkNext(ls,SMALL_RIGHT);
            return expr;
        } else if(ls.getCurTokenEnum() == NAME){
            String s = ls.getCurrTk().getS();
            luaX_Next(ls);
            return new NameExpr(s);
        }
        return null;
    }

    public static StringExpr fieldSel(LexState ls){
        luaX_Next(ls);
        StringExpr stringExpr =new StringExpr(ls.getCurrTk().getS());
        luaX_Next(ls);
        return  stringExpr;
    }

    /**
     * suffixedexp -> primaryexp { '.' NAME | '[' exp ']' | ':' NAME funcargs | funcargs }
     */
    public static Expr suffixedExp(LexState ls){
        Expr suffixedExp = primaryExpr(ls);
        if(suffixedExp == null){
            return null;
        }

        for(;;){
            switch (ls.getCurTokenEnum()){
                case DOT:
                    StringExpr key = fieldSel(ls);
                    suffixedExp = new TableStrAccess(suffixedExp,key);
                    break;
                case MID_LEFT: {
                    Expr k = tableIndex(ls);
                    suffixedExp = new TableExprAccess(suffixedExp,k);
                    break;
                }
                case COLON: {
                    StringExpr method = fieldSel(ls);
                    FuncArgs funcArgs = funcArgs(ls);
                    suffixedExp = new TableMethodCall(suffixedExp,method,funcArgs);
                    break;
                }
                case SMALL_LEFT: case BIG_LEFT: case STRING:
                    suffixedExp = new FuncCall(suffixedExp,funcArgs(ls));
                    break;
                default:
                   return suffixedExp;
            }
        }

    }
    public static TableConstructor constructor(LexState ls){
        TableConstructor cons  = new TableConstructor();
        checkNext(ls,BIG_LEFT);
        do {
            if(ls.getCurTokenEnum() == BIG_RIGHT){
                break;
            }
            Field field = field(ls);
            if(field.getTableField() != null){
                cons.addTableFields(field.getTableField()) ;
            } else{
                cons.addTableListFieds(field.getTableListField());
            }

        }while(testNext(ls,COMMA) || testNext(ls,SEMICON));

        checkNext(ls,BIG_RIGHT);
        return cons;
    }
    public static FuncArgs funcArgs(LexState ls){
        FuncArgs funcArgs;
        switch (ls.getCurTokenEnum()){
            case SMALL_LEFT:
                luaX_Next(ls);
                if(ls.getCurTokenEnum() == SMALL_RIGHT){
                    funcArgs = new FuncArgs();
                } else{
                    funcArgs = new FuncArgs();
                    List<Expr> exprList = exprList(ls);
                    funcArgs.addExprList(exprList);
                }
                luaX_Next(ls);
                break;
            case BIG_LEFT:
                TableConstructor constructor = constructor(ls);
                funcArgs = new FuncArgs(constructor);
                break;
            case STRING:
                funcArgs = new FuncArgs(new StringExpr(ls.getCurrTk().getS()));
                luaX_Next(ls);
                break;
            default:
                    funcArgs = null;
                    System.err.println("lost func args");

        }
        return funcArgs;
    }
    public static TableListField listField(LexState ls){
        return new TableListField(expr(ls));
    }
    public static TableField recField(LexState ls){
        Expr left,right;
        if(ls.getCurTokenEnum() == NAME){
            left = new NameExpr(ls.getCurrTk().getS());
            luaX_Next(ls);
        } else{
            left = tableIndex(ls);
        }
        checkNext(ls,ASSIGN);
        right = expr(ls);
        return new TableField(left,right);
    }

    public static Field field(LexState ls) {
        Field f;
        switch (ls.getCurTokenEnum()) {
            case NAME:
                if (luaX_lookahead(ls) != ASSIGN) {
                    f = new Field(listField(ls));
                } else {
                    f = new Field(recField(ls));
                }
                break;
            case MID_LEFT:
                f = new Field(recField(ls));
                break;
            default:
                f = new Field(listField(ls));
        }
        return f;
    }
    public static Expr tableIndex(LexState ls){
        luaX_Next(ls);
        Expr e = expr(ls);
        luaX_Next(ls);
        return e;
    }
}

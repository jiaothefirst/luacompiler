package com.jdy.lua.lcodes2;

import com.jdy.lua.lobjects.TValue;
import com.jdy.lua.lopcodes.Instruction;
import com.jdy.lua.lopcodes.Instructions;
import com.jdy.lua.lopcodes.OpCode;
import com.jdy.lua.lparser2.ArgAndKind;
import com.jdy.lua.lparser2.FunctionInfo;
import com.jdy.lua.lparser2.VirtualLabel;
import com.jdy.lua.lparser2.expr.*;
import com.jdy.lua.lparser2.statement.*;

import java.util.ArrayList;
import java.util.List;

import static com.jdy.lua.lcodes.BinOpr.*;
import static com.jdy.lua.lopcodes.OpCode.*;

@SuppressWarnings("all")
public class InstructionGenerator {

    private FunctionInfo fi;

    private int exprLevel = 0;

    private boolean isStatement() {
        return exprLevel == 0;
    }
    public void executeLogicExpr(Expr expr,ExprDesc desc){
        if (expr instanceof LogicExpr) {
            expr.generate(this, desc);
        } else {
            //为普通表达式生成 test指令
            testOp(desc, expr);
        }
    }
    public void generateLogicExpr(Expr expr, ExprDesc desc) {
        exprLevel++;
        desc.setTrueLabel(new VirtualLabel());
        desc.setFalseLabel(new VirtualLabel());
        executeLogicExpr(expr,desc);
        exprLevel--;
        if (desc.isJump()) {
            desc.getTrueLabel().addInstruction(fi, desc.getInfo());
        }
        int falseJmp = Lcodes.emitCodeABC(fi, OP_LFALSESKIP, desc.getReg(), 0, 0);
        desc.getFalseLabel().fixJump2Pc(falseJmp);
        int trueJmp = Lcodes.emitCodeABC(fi, OP_LOADTRUE, desc.getReg(), 0, 0);
        desc.getTrueLabel().fixJump2Pc(trueJmp);
    }

    public ExprDesc generateExpr(Expr expr) {
        exprLevel++;
        expr.generate(this);
        ExprDesc desc = new ExprDesc();
        exprLevel--;
        return desc;
    }

    public void generateLogicStatement(Expr expr, VirtualLabel trueLabel, VirtualLabel falseLabel, ExprDesc desc) {
        desc.setTrueLabel(trueLabel);
        desc.setFalseLabel(falseLabel);
        executeLogicExpr(expr,desc);
        // expr结尾是 loadFalse,loadTrue， statement结尾处理成 trueConditin,falseCondition 反过来
        if (desc.isJump()) {
            desc.getFalseLabel().addInstruction(fi,desc.getInfo());
        }
        //反转上个jump指令
        int pc = fi.getPc();
        negative(pc);
        //如果上个jump指令在 TrueLabel，进行删除
        desc.getTrueLabel().removeInstruction(pc, fi.getInstruction(pc));
    }

    public ExprDesc generateStatement(Expr expr) {
        expr.generate(this);
        return null;
    }


    public InstructionGenerator(FunctionInfo fi) {
        this.fi = fi;
    }

    public void generate(Expr expr, ExprDesc exprDesc) {
        // do nothindg
    }

    public void generate(Statement statement) {
       // do nothing
    }

    public void generate(BlockStatement blockStatement) {
        StatList statList = blockStatement.getStatList();
        statList.generate(this);
    }

    /**
     * local 函数定义
     */
    public void generate(LocalFuncStat funcStat) {
        int r = fi.addLocVar(funcStat.getStr(), fi.getPc() + 2);
        funcStat.getFunctionBody().generate(this, createDesc(r, 0));
    }

    /**
     * 函数定义
     */
    public void generate(FunctionStat functionStat) {
        int oldRegs = fi.getUsedRegs();
        ExprDesc exprDesc = new ExprDesc();
        exprDesc.setGlobalFunc(true);
        if (functionStat.getFieldDesc() != null && functionStat.getFieldDesc().size() > 0) {
            List<StringExpr> stringExprs = functionStat.getFieldDesc();
            //将结果存储在寄存器a里面
            int a = fi.allocReg();
            //a.b.c.d=xx，先生成 a[b] b[c] c[d],再调整最后一个为c[d] =xx
            tableAccess(functionStat.getVar(), stringExprs.get(0), a);
            //getfield不占用寄存器
            for (int i = 1; i < stringExprs.size(); i++) {
                int key = exp2ArgAndKind(fi, stringExprs.get(i), ArgAndKind.ARG_CONST).getArg();
                Lcodes.emitCodeABC(fi, OpCode.OP_GETFIELD, a, a, key);
            }
            //获取上个指令
            Instruction lastIns = fi.getInstruction(fi.getPc());
            int argB = Instructions.getArgB(lastIns);
            int argC = Instructions.getArgC(lastIns);
            int funcReg = fi.allocReg();
            //tableAccess还原了寄存器，这里要防止有冲突
            if (funcReg == argC) {
                funcReg = fi.allocReg();
            }
            exprDesc.setReg(funcReg);
            functionStat.getFunctionBody().generate(this, exprDesc);

            OpCode code = Instructions.getOpCode(lastIns);
            if (code == OpCode.OP_GETTABLE) {
                Instructions.setOpCode(lastIns, OpCode.OP_SETTABLE);
            } else if (code == OpCode.OP_GETTABUP) {
                Instructions.setOpCode(lastIns, OpCode.OP_SETTABUP);
            } else {
                Instructions.setOpCode(lastIns, OpCode.OP_SETFIELD);
            }
            Instructions.setArgA(lastIns, argB);
            Instructions.setArgB(lastIns, argC);
            Instructions.setArgC(lastIns, funcReg);
            fi.setUsedRegs(oldRegs);
        } else {
            String varName = functionStat.getVar().getName();
            int funcReg = fi.allocReg();
            exprDesc.setReg(funcReg);
            functionStat.getFunctionBody().generate(this, exprDesc);
            fi.freeReg();
            int a = fi.slotOfLocVar(varName);
            if (a >= 0) {
                Lcodes.emitCodeABC(fi, OpCode.OP_MOVE, a, funcReg, 0);
                return;
            }

            int b = fi.indexOfUpval(varName);
            if (b >= 0) {
                Lcodes.emitCodeABC(fi, OpCode.OP_SETUPVAL, funcReg, b, 0);
                return;
            }
            int env = fi.slotOfLocVar("_ENV");
            if (env >= 0) {
                b = fi.indexOfConstant(TValue.strValue(varName));
                Lcodes.emitCodeABC(fi, OpCode.OP_SETFIELD, env, b, funcReg);
                return;
            }
            //全局变量
            env = fi.indexOfUpval("_ENV");
            Lcodes.emitCodeABC(fi, OP_SETUPVAL, env, b, funcReg);
        }
    }


    public void generate(StatList statList) {
        for (Statement statement : statList.getStatements()) {
            statement.generate(this);
        }
    }

    public void generate(BlockStatement blockStatement, FunctionInfo fi) {
        blockStatement.getStatList().generate(this);
    }

    public void generate(RepeatStatement repeatStatement) {
        VirtualLabel trueLabel = new VirtualLabel();
        VirtualLabel falseLabel = new VirtualLabel();
        fi.enterScope(true);
        int beforeRepeat = fi.getPc();
        repeatStatement.getBlock().generate(this);
        generateLogicStatement(repeatStatement.getCond(), trueLabel, falseLabel, new ExprDesc());
        //跳到 repeat的block部分
        Lcodes.emitCodeJump(fi, beforeRepeat - fi.getPc() - 1, 0);
        trueLabel.fixJump2Pc(fi.getPc());
        fi.closeOpnUpval();
        fi.exitScope(fi.getPc() + 1);
        falseLabel.fixJump2Pc(fi.getPc() + 1);
    }
    public static boolean isTableAccess(Expr expr){
        return expr instanceof TableStrAccess || expr instanceof TableExprAccess;
    }
    public static boolean isCall(Expr expr){
       return expr instanceof FuncCall || expr instanceof TableMethodCall;
    }
    public void generate(ReturnStatement returnStatement) {
        if (returnStatement.getExprList() == null) {
            Lcodes.emitCodeABC(fi, OpCode.OP_RETURN, 0, 1, 0);
            return;
        }
        List<Expr> exprs = returnStatement.getExprList();
        int nExprs = exprs.size();
        if (nExprs == 1) {
            if (isCall(exprs.get(0))) {
                int r = fi.allocReg();
                ExprDesc exprDesc = new ExprDesc();
                exprDesc.setReg(r);
                generate(exprs.get(0),exprDesc);
                Lcodes.emitCodeABC(fi, OpCode.OP_TAILCALL, r,exprDesc.getInfo() + 1, 0);
                fi.freeReg();
                Lcodes.emitCodeABC(fi, OpCode.OP_RETURN, r, 0, 0);
                return;
            }
            int r = exp2ArgAndKind(fi,exprs.get(0),ArgAndKind.ARG_REG).getArg();
            fi.freeReg();
            Lcodes.emitCodeABC(fi, OP_RETURN1,r,0,0);
            return;
        }

        boolean multRet = hasMultiRet(exprs.get(exprs.size() - 1));
        for (int i = 0; i < nExprs; i++) {
            Expr expr = exprs.get(i);
            int r = fi.allocReg();
            if (i == nExprs - 1 && multRet) {
                expr.generate(this, createDesc(r, -1));
            } else {
                expr.generate(this, createDesc(r, 1));
            }
        }
        fi.freeReg(nExprs);

        int a = fi.getUsedRegs();
        if (multRet) {
            Lcodes.emitCodeABC(fi, OpCode.OP_RETURN, a, 0, 0);
        } else {
            Lcodes.emitCodeABC(fi, OpCode.OP_RETURN, a, nExprs + 1, 0);
        }
    }

    public void generate(BreakStatement b) {
        int pc = Lcodes.emitCodeJump(fi, 0, 0);
        fi.addBreakJmp(pc);
    }

    public void generate(WhileStatement whileStatement) {
        int beforeWhile = fi.getPc();
        VirtualLabel trueLabel = new VirtualLabel();
        VirtualLabel falseLabel = new VirtualLabel();
        generateLogicStatement(whileStatement.getCond(), trueLabel, falseLabel, new ExprDesc());
        fi.enterScope(true);
        trueLabel.fixJump2Pc(fi.getPc() + 1);
        whileStatement.getBlock().generate(this);
        //跳转到While开头
        Lcodes.emitCodeJump(fi, beforeWhile - 1 - fi.getPc(), 0);
        fi.closeOpnUpval();
        fi.exitScope(fi.getPc());
        falseLabel.fixJump2Pc(fi.getPc() + 1);
    }

    public void generate(IfStatement ifStatement) {
        VirtualLabel endLabel = new VirtualLabel();
        VirtualLabel trueLabel;
        VirtualLabel falseLabel = null;
        List<Expr> conds = ifStatement.getAllConds();
        List<BlockStatement> blocks = ifStatement.getAllIfBlock();
        int n = conds.size();
        for (int i = 0; i < n; i++) {
            if (falseLabel != null) {
                falseLabel.fixJump2Pc(fi.getPc() + 1);
            }
            trueLabel = new VirtualLabel();
            falseLabel = new VirtualLabel();
            generateLogicStatement(conds.get(i), trueLabel, falseLabel, new ExprDesc());
            trueLabel.fixJump2Pc(fi.getPc()+1);
            fi.enterScope(true);
            blocks.get(i).generate(this);
            fi.closeOpnUpval();
            fi.exitScope(fi.getPc() + 1);

            if (ifStatement.getElseBlock() != null || i != n - 1) {
                //添加一条跳出if语句的jmp，没有else block就不再生成最后一个jmp
                int jmp2End = Lcodes.emitCodeJump(fi, 0, 0);
                endLabel.addInstruction(fi, jmp2End);
            }
        }
        if (falseLabel != null) {
            falseLabel.fixJump2Pc(fi.getPc() + 1);
        }
        if (ifStatement.getElseBlock() != null) {
            ifStatement.getElseBlock().generate(this);
        }
        endLabel.fixJump2Pc(fi.getPc() + 1);
    }

    public void removeTailNils(List<Expr> exprs) {
        while (!exprs.isEmpty() && exprs.get(exprs.size() - 1) instanceof NilExpr) {
            exprs.remove(exprs.size() - 1);
        }
    }


    public void generate(ExprStatement exprStatement) {
        //函数调用
        if (exprStatement.getFunc() != null) {
            int r = fi.allocReg();
            exprStatement.getFunc().generate(this, createDesc(r, 0));
            fi.freeReg();
            return;
        }
        List<Expr> vars = exprStatement.getLefts();
        List<Expr> exprs = exprStatement.getRights();

        removeTailNils(exprStatement.getRights());
        int nVars = exprStatement.getLefts().size();
        int nExps = exprs.size();
        int[] tableRegs = new int[nVars];
        int[] keyRegs = new int[nVars];
        //将值存放到varRegs
        int[] varRegs = new int[nVars];
        int oldRegs = fi.getUsedRegs();

        for (int i = 0; i < vars.size(); i++) {
            Expr expr = vars.get(i);
            if (isTableAccess(expr)) {
                tableRegs[i] = fi.allocReg();
                keyRegs[i] = fi.allocReg();
                ExprDesc desc = new ExprDesc();
                desc.setTableReg(tableRegs[i]);
                desc.setTableKey(keyRegs[i]);
                tableAccessLeft(expr,desc);
            } else if (expr instanceof NameExpr) {
                NameExpr nameExpr = (NameExpr) expr;
                String name = nameExpr.getName();
                if (fi.slotOfLocVar(name) < 0 && fi.indexOfUpval(name) < 0) {
                    keyRegs[i] = -1;
                    //全局变量
                    if (fi.indexOfConstant(TValue.strValue(name)) > 0xFF) {
                        keyRegs[i] = fi.allocReg();
                    }
                }
            } else {
                System.err.println("错误的ExprStatement");
            }
        }
        //事先存储好变量值的寄存器，后面才会 alloc
        for (int i = 0; i < vars.size(); i++) {
            varRegs[i] = fi.getUsedRegs() + i;
        }
        if (nExps >= nVars) {
            for (int i = 0; i <nVars; i++) {
                Expr exp = exprs.get(i);
                int a = fi.allocReg();
                if (i >= nVars && i == nExps - 1 && hasMultiRet(exp)) {
                    exp.generate(this, createDesc(a, 0));
                } else {
                    exp.generate(this, createDesc(a, 1));
                }
            }
        } else {
            boolean multRet = false;
            for (int i = 0; i < exprs.size(); i++) {
                Expr exp = exprs.get(i);
                int a = fi.allocReg();
                if (i == nExps - 1 && hasMultiRet(exp)) {
                    multRet = true;
                    //计算出表达式返回值的数量
                    int n = nVars - nExps + 1;
                    exp.generate(this, createDesc(a, n));
                    //接受返回值
                    fi.allocReg(n - 1);
                } else {
                    exp.generate(this, createDesc(a, 1));
                }
            }
            //置空
            if (!multRet) {
                int n = nVars - nExps;
                int a = fi.allocReg(n);
                Lcodes.emitCodeABC(fi, OpCode.OP_LOADNIL, a, n - 1, 0);
            }
        }
        for (int i = 0; i < nVars; i++) {
            Expr expr = vars.get(i);
            if (isTableAccess(expr)) {
                Lcodes.emitCodeABC(fi, OpCode.OP_SETTABLE, tableRegs[i], keyRegs[i], varRegs[i]);
                continue;
            }
            NameExpr nameExpr = (NameExpr) expr;
            String varName = nameExpr.getName();
            int a = fi.slotOfLocVar(varName);
            if (a >= 0) {
                Lcodes.emitCodeABC(fi, OpCode.OP_MOVE, a, varRegs[i], 0);
                continue;
            }

            int b = fi.indexOfUpval(varName);
            if (b >= 0) {
                Lcodes.emitCodeABC(fi, OpCode.OP_SETUPVAL, varRegs[i], b, 0);
                continue;
            }
            int env = fi.slotOfLocVar("_ENV");
            if (env >= 0) {
                b = fi.indexOfConstant(TValue.strValue(varName));
                Lcodes.emitCodeABC(fi, OpCode.OP_SETFIELD, env, b, varRegs[i]);
                continue;
            }
            //全局变量
            env = fi.indexOfUpval("_ENV");
            b = fi.indexOfConstant(TValue.strValue(varName));
            Lcodes.emitCodeABC(fi, OP_SETTABUP, env, b, varRegs[i]);
        }
        //赋值表达式没有定义变量，将寄存器还原
        fi.setUsedRegs(oldRegs);
    }

    public void generate(FunctionBody functionBody, ExprDesc desc) {
        FunctionInfo subFunc = new FunctionInfo();
        InstructionGenerator instructionGenerator = new InstructionGenerator(subFunc);
        fi.addFunc(subFunc);
        if (functionBody.isMethod()) {
            subFunc.addLocVar("self", 0);
        }
        for (NameExpr nameExpr : functionBody.getParList().getNameExprs()) {
            subFunc.addLocVar(nameExpr.getName(), 0);
        }
        if(functionBody.getParList().isHasVararg()){
           Lcodes.emitCodeABC(subFunc, OP_VARARGPREP,functionBody.getParList().getNameExprs().size(),0,0);
        }

        functionBody.getBlock().generate(instructionGenerator, subFunc);
        subFunc.exitScope(subFunc.getPc() + 2);
        Lcodes.emitCodeABC(subFunc, OpCode.OP_RETURN, 0, 1, 0);
        int bx = fi.getSubFuncs().size() - 1;
        //全局函数存放在env里面
        if(!desc.isGlobalFunc()) {
            Lcodes.emitCodeABx(fi, OpCode.OP_CLOSURE, desc.getReg(), bx);
        }
        subFunc.setGlobal(desc.isGlobalFunc());
    }

    public void generate(LocalStatement statement) {
        removeTailNils(statement.getExprList());
        int oldRegs = fi.getUsedRegs();
        List<Expr> exprList = statement.getExprList();
        List<String> varNames = statement.getNames();
        int nExps = exprList.size();
        int nNames = varNames.size();

        //表达式数量和变量数量一致，依次存放到寄存器里面
        if (nExps >= nNames) {
            //多于变量数目的表达式忽略
            for (int i=0;i<nNames;i++) {
               exp2ArgAndKind(fi, exprList.get(i), ArgAndKind.ARG_REG);
            }
        } else {
            boolean hasMulRet = false;
            for (int i = 0; i < nExps; i++) {
                Expr expr = exprList.get(i);
                int tempReg = fi.allocReg();
                if (i == nExps - 1 && hasMultiRet(expr)) {
                    hasMulRet = true;
                    int n = nNames - nExps + 1;
                    expr.generate(this, createDesc(tempReg, n));
                    //为多个返回值，分配空间
                    fi.allocReg(n - 1);
                } else {
                    expr.generate(this, createDesc(tempReg, 1));
                }
            }
            //置nil
            if (!hasMulRet) {
                int nilNum = nNames - nExps;
                int tempReg = fi.allocReg(nilNum);
                Lcodes.emitCodeABC(fi, OpCode.OP_LOADNIL, tempReg, nilNum - 1, 0);
            }
        }
        fi.setUsedRegs(oldRegs);
        int startPc = fi.getPc() + 1;
        for (String varName : varNames) {
            fi.addLocVar(varName, startPc);
        }
    }

    public void generate(TableConstructor constructor, ExprDesc exprDesc) {
        //数组部分大小
        int nArr = constructor.getListFields().size();
        //map部分大小
        int nExp = constructor.getFields().size();

        boolean hasMulRet = false;
        if (nArr != 0) {
            hasMulRet =  hasMultiRet(constructor.getListFields().get(0));
        }
        if (nExp != 0) {
            hasMulRet = hasMulRet || hasMultiRet(constructor.getFields().get(0));
        }
        Lcodes.emitCodeABC(fi, OpCode.OP_NEWTABLE, exprDesc.getReg(), nArr, nExp);
        //处理数组部分
        for (int i = 1; i <= nArr; i++) {
            Expr listField = constructor.getListFields().get(i - 1).getExpr();
            int tmp = fi.allocReg();
            if (i == nArr && hasMulRet) {
                listField.generate(this, createDesc(tmp, -1));
            } else {
                listField.generate(this, createDesc(tmp, 1));
            }
            if (i % 50 == 0 || i == nArr) {
                int reg = i % 50;
                if (reg == 0) {
                    reg = 50;
                }
                fi.freeReg(reg);
                int c = (i - 1) / 50 + 1;
                if (i == nArr && hasMulRet) {
                    Lcodes.emitCodeABC(fi, OpCode.OP_SETLIST, exprDesc.getReg(), 0, c);
                } else {
                    Lcodes.emitCodeABC(fi, OpCode.OP_SETLIST, exprDesc.getReg(), reg, c);
                }
            }
        }
        //处理 table部分
        for (int i = 1; i <= nExp; i++) {
            TableField field = constructor.getFields().get(i - 1);
            Expr left = field.getLeft();
            Expr right = field.getRight();
            int b = fi.allocReg();
            left.generate(this, createDesc(b, 1));
            int c = fi.allocReg();
            right.generate(this, createDesc(c, 1));
            fi.freeReg(2);
            Lcodes.emitCodeABC(fi, OpCode.OP_SETTABLE, exprDesc.getReg(), b, c);
        }

    }


    private void forNum(ForStatement forStatement) {
        if (forStatement.getExpr3() == null) {
            //默认步长
            Expr expr = new IntExpr(1L);
            forStatement.setExpr3(expr);
        }
        fi.enterScope(true);
        //expr1,expr2,expr3只在一开始执行一次，将其存放在三个自定义变量中
        LocalStatement localStatement = LocalStatement.builder()
                .addLocalVar("(for index)",forStatement.getExpr1())
                .addLocalVar("(for limit)",forStatement.getExpr2())
                .addLocalVar("(for step)",forStatement.getExpr3()).build();
        generate(localStatement);
        fi.addLocVar(forStatement.getName1().getName(), fi.getPc() + 2);
        //一共有四个变量，OP_FORPREP准备好计数器，并检查能否执行循环，否则就跳出循环
        int a = fi.getUsedRegs() - 4;
        int pcForPrep = Lcodes.emitCodeABx(fi, OpCode.OP_FORPREP, a, 0);
        forStatement.getBlock().generate(this);
        fi.closeOpnUpval();
        //OP_FORLOOP,更新计数器,如果循环继续，跳到循环体
        int pcForLoop = Lcodes.emitCodeABx(fi, OpCode.OP_FORLOOP, a, 0);

        Instruction prep = fi.getInstruction(pcForPrep);
        Instruction loop = fi.getInstruction(pcForLoop);
        //向下跳出循环
        Instructions.setArgBx(prep, pcForLoop - pcForPrep - 1);
        //向上跳到循环体
        Instructions.setArgBx(loop, pcForLoop - pcForPrep );
        fi.exitScope(fi.getPc());

        fi.fixEndPC("(for index)", 1);
        fi.fixEndPC("(for limit)", 1);
        fi.fixEndPC("(for step)", 1);
    }


    /**
     * for var1,var2... in exprlist do block end
     *
     * exprlist 会产生
     *   iterator function ,state,control variable的 初始值, a closing value（这里不处理,因此只考虑三个变量即可)
     *   iterator function调用时使用 state,control variable作为参数，返回值会赋值给
     *   var1,var2...,  如果control variable 为nil了，循环终止
     *
     */
    private void forIn(ForStatement forStatement) {
        fi.enterScope(true);
        LocalStatement localStatement = LocalStatement.builder()
                .addVarName("(for generator)")
                .addVarName("(for state)")
                .addVarName("(for control)")
                .setExprList(forStatement.getExprList()).build();
        localStatement.generate(this);

        for (NameExpr expr : forStatement.getNameExprList()) {
            fi.addLocVar(expr.getName(), fi.getPc() + 2);
        }
        int pcJmpToTFC = Lcodes.emitCodeJump(fi, 0, 0);
        forStatement.getBlock().generate(this);
        fi.closeOpnUpval();
        Instruction tfcIns = fi.getInstruction(pcJmpToTFC);
        Instructions.setArgsJ(tfcIns, fi.getPc() - pcJmpToTFC);
        int rGenerator = fi.slotOfLocVar("(for generator)");
        Lcodes.emitCodeABC(fi, OpCode.OP_TFORCALL, rGenerator, 0, forStatement.getNameExprList().size());
        //跳到循环开始的部分
        Lcodes.emitCodeABx(fi, OpCode.OP_TFORLOOP, rGenerator + 2, fi.getPc() +1 - pcJmpToTFC );
        fi.exitScope(fi.getPc() - 1);
        fi.fixEndPC("(for generator)", 2);
        fi.fixEndPC("(for state)", 2);
        fi.fixEndPC("(for control)", 2);

    }

    public void generate(ForStatement forStatement) {
        if (forStatement.isGeneric()) {
            forIn(forStatement);
        } else {
            forNum(forStatement);
        }
    }

    public void generate(TableStrAccess tableStrAccess,ExprDesc exprDesc){
        tableAccess(tableStrAccess.getTable(),tableStrAccess.getKey(), exprDesc.getReg());
    }
    public void generate(TableExprAccess tableExprAccess,ExprDesc exprDesc){
        tableAccess(tableExprAccess.getTable(),tableExprAccess.getKey(),exprDesc.getReg());
    }
    public void generate(FuncCall funcCall,ExprDesc exprDesc){
        int nArgs = prepareFuncCall(funcCall.getFunc(), null, funcCall.getArgs(),exprDesc.getReg());
        exprDesc.setInfo(nArgs);
        Lcodes.emitCodeABC(fi, OpCode.OP_CALL,exprDesc.getReg(), nArgs + 1, exprDesc.getN() + 1);
    }
    public void generate(TableMethodCall tableMethodCall,ExprDesc exprDesc){
        int nArgs = prepareFuncCall(tableMethodCall.getTable(),tableMethodCall.getMethod(),
                tableMethodCall.getArgs(),exprDesc.getReg());
        exprDesc.setInfo(nArgs);
        //b c 分别为参数数量 和 函数的返回值数量
        Lcodes.emitCodeABC(fi, OpCode.OP_CALL, exprDesc.getReg(), nArgs + 1,exprDesc.getN() + 1);
    }


    public static boolean hasMultiRet(Expr expr) {
        if (expr instanceof VarargExpr) {
            return true;
        }
        return isCall(expr);
    }

    private int prepareFuncCall(Expr expr, StringExpr name, FuncArgs args, int a) {
        List<Expr> exprList = new ArrayList<>();
        //函数参数有三种类型 a "hello"  a(x,x,x)  a{a=1,b=2,c=3}
        if (args.getExpr1().size() != 0) {
            exprList = args.getExpr1();
        } else if (args.getConstructor() != null) {
            exprList.add(args.getConstructor());
        } else if (args.getStringExpr() != null) {
            exprList.add(args.getStringExpr());
        }
        int nArgs = exprList.size();
        boolean hasMultiRet = false;

        expr.generate(this, createDesc(a, 1));
        //method Call
        if (name != null) {
            fi.allocReg();
            ArgAndKind argAndKindC = exp2ArgAndKind(fi, name, ArgAndKind.ARG_REG);
            Lcodes.emitCodeABC(fi, OpCode.OP_SELF, a, a, argAndKindC.getArg());
            fi.freeReg(1);
        }
        for (int i = 0; i < exprList.size(); i++) {
            Expr ex = exprList.get(i);
            int tempReg = fi.allocReg();
            if (i == exprList.size() - 1 && hasMultiRet(ex)) {
                hasMultiRet = true;
                ex.generate(this, createDesc(tempReg, -1));
            } else {
                ex.generate(this, createDesc(tempReg, -1));
            }
        }
        fi.freeReg(nArgs);

        if (name != null) {
            nArgs++;
        }

        if (hasMultiRet) {
            nArgs = -1;
        }
        return nArgs;
    }

    /**
     * exrp[key] =valu
     */
    private void tableSet(Expr t, Expr k, Expr v) {
        int oldRegs = fi.getUsedRegs();
        ArgAndKind argAndKind = exp2ArgAndKind(fi, t, ArgAndKind.ARG_RU);
        int a = argAndKind.getArg();
        int b = exp2ArgAndKind(fi, k, ArgAndKind.ARG_RK).getArg();
        int c = exp2ArgAndKind(fi, v, ArgAndKind.ARG_REG).getArg();
        if (argAndKind.getKind() == ArgAndKind.ARG_REG) {
            Lcodes.emitCodeABC(fi, OpCode.OP_SETTABLE, a, b, c);
        } else {
            Lcodes.emitCodeABC(fi, OpCode.OP_SETTABUP, a, b, c);
        }
        fi.setUsedRegs(oldRegs);
    }

    /**
     * 当在左边出现 talbe[key]说明是赋值，
     */
    private void tableAccessLeft(Expr expr,ExprDesc exprDesc){
        int table =exprDesc.getTableReg();
        int key =  exprDesc.getTableKey();
        if(expr instanceof TableExprAccess){
            TableExprAccess exprAccess = (TableExprAccess)expr;
            exprAccess.getTable().generate(this,createDesc(table,1));
            exprAccess.getKey().generate(this,createDesc(key,1));
        } else if(expr instanceof TableStrAccess){
            TableStrAccess strAccess = (TableStrAccess)expr;
            strAccess.getTable().generate(this,createDesc(table,1));
            strAccess.getKey().generate(this,createDesc(key,1));
        }
    }
    /**
     * exp[key]
     */
    private void tableAccess(Expr exp, Expr key, int a) {
        int oldRegs = fi.getUsedRegs();
        ArgAndKind argAndKindB = exp2ArgAndKind(fi, exp, ArgAndKind.ARG_RU);
        int b = argAndKindB.getArg();
        ArgAndKind argAndKindC = exp2ArgAndKind(fi, key, ArgAndKind.ARG_RK);
        int c = argAndKindC.getArg();
        fi.setUsedRegs(oldRegs);
        if (argAndKindB.getKind() == ArgAndKind.ARG_REG) {
            if (argAndKindC.getKind() == ArgAndKind.ARG_REG) {
                Lcodes.emitCodeABC(fi, OpCode.OP_GETTABLE, a, b, c);
            } else {
                Lcodes.emitCodeABC(fi, OpCode.OP_GETFIELD, a, b, c);
            }
        } else {
            Lcodes.emitCodeABC(fi, OpCode.OP_GETTABUP, a, b, c);
        }

    }

    public void generate(NameExpr expr,ExprDesc desc) {
        int r = fi.slotOfLocVar(expr.getName());
        if (r >= 0) {
            Lcodes.emitCodeABC(fi, OpCode.OP_MOVE, desc.getReg(), r, 0);
            return;
        }
        r = fi.indexOfUpval(expr.getName());
        if (r >= 0) {
            Lcodes.emitCodeABC(fi, OpCode.OP_GETUPVAL,desc.getReg(), r, 0);
            return;
        }
        //env['name'],env存放全局的东西，使用字符串常量去存放内容
        //同时也有env[env]=env的规则，env是最外层函数的一个'local var'
        NameExpr expr1 = new NameExpr("_ENV");
        tableAccess(expr1, new StringExpr(expr.getName()),desc.getReg());
    }

    public void generate(NotExpr notExpr,ExprDesc exprDesc){
        Expr expr =notExpr.getLeft();
        if(expr instanceof LogicExpr){
            expr.generate(this,exprDesc);
        } else{
            testOp(exprDesc,expr);
        }
        if(exprDesc.isJump()){
            exprDesc.setJump(false);
            //根据是statement还是expr加入不同的lable中
            if(!isStatement()) {
                exprDesc.getFalseLabel().addInstruction(fi, exprDesc.getInfo());
            } else{
                exprDesc.getTrueLabel().addInstruction(fi,exprDesc.getInfo());
            }
        }
        negative(exprDesc.getInfo());
        exprDesc.exchangeLabel();
    }

    public void jumpCond(ExprDesc exprDesc, Expr expr, boolean cond) {
        int b = exp2ArgAndKind(fi, expr, ArgAndKind.ARG_REG).getArg();
        if (!isStatement()) {
            Lcodes.emitCodeABCK(fi, OP_TESTSET, exprDesc.getReg(), b, 0, cond ? 1 : 0);
        } else {
            Lcodes.emitCodeABCK(fi, OP_TEST, b, 0, 0, cond ? 1 : 0);
        }
        int jmp = Lcodes.emitCodeJump(fi, 0, 0);
        exprDesc.setInfo(jmp);
        fi.freeReg();
    }

    public void testOp(ExprDesc exprDesc, Expr expr) {
        int b = exp2ArgAndKind(fi, expr, ArgAndKind.ARG_REG).getArg();
        if (!isStatement()) {
            Lcodes.emitCodeABCK(fi, OP_TESTSET, exprDesc.getReg(), b, 0, 1);
        } else {
            Lcodes.emitCodeABCK(fi, OP_TEST, b, 0, 0, 1);
        }
        int jmp = Lcodes.emitCodeJump(fi, 0, 0);
        exprDesc.setInfo(jmp);
        exprDesc.setJump(true);
        fi.freeReg();
    }

    /**
     * 反转指令s
     */
    public void negative(int jmp) {
        Instruction jmpControl = fi.getInstruction(jmp - 1);
        Instructions.setArgk(jmpControl, Instructions.getArgk(jmpControl) ^ 1);
    }

    public void generate(LogicExpr logicExpr, ExprDesc exprDesc) {

        ExprDesc left = new ExprDesc();
        VirtualLabel curLabel = new VirtualLabel();

        if (logicExpr.getOp() == OPR_AND) {
            left.setTrueLabel(curLabel);
            left.setFalseLabel(exprDesc.getFalseLabel());
        } else {
            left.setFalseLabel(curLabel);
            left.setTrueLabel(exprDesc.getTrueLabel());
        }
        int oldRegs = fi.getUsedRegs();


        //逻辑运算
        if (logicExpr.getLeft() instanceof LogicExpr) {
            logicExpr.getLeft().generate(this, left);
        } else {
            //TEST运算
            jumpCond(left, logicExpr.getLeft(), logicExpr.getOp() == OPR_OR);
        }
        //pc指向的是jmp，应该跳转到jmp的下一条
        curLabel.fixJump2Pc(fi.getPc() + 1);

        if (logicExpr.getOp() == OPR_AND) {
            if (left.isJump()) {
                negative(left.getInfo());
                left.setJump(false);
            }
            exprDesc.getFalseLabel().addInstructionList(left.getFalseLabel());
            exprDesc.getFalseLabel().addInstruction(fi, left.getInfo());
        } else {
            left.setJump(false);
            exprDesc.getTrueLabel().addInstructionList(left.getTrueLabel());
            exprDesc.getTrueLabel().addInstruction(fi, left.getInfo());
        }

        if (logicExpr.getRight() instanceof LogicExpr) {
            logicExpr.getRight().generate(this, exprDesc);
        } else {
            testOp(exprDesc, logicExpr.getRight());
        }
        if (logicExpr.getOp() == OPR_AND) {
            exprDesc.getFalseLabel().addInstruction(fi, exprDesc.getInfo());
        } else {
            exprDesc.getTrueLabel().addInstruction(fi, exprDesc.getInfo());
        }
        fi.setUsedRegs(oldRegs);
    }



    public void generate(RelationExpr relationExpr, ExprDesc exprDesc) {
        int b = exp2ArgAndKind(fi, relationExpr.getLeft(), ArgAndKind.ARG_REG).getArg();
        int c = exp2ArgAndKind(fi, relationExpr.getRight(), ArgAndKind.ARG_REG).getArg();
        switch (relationExpr.getOp()) {
            case OPR_EQ:
                Lcodes.emitCodeABCK(fi, OP_EQ, b, c, 0, 1);
                break;
            case OPR_NE:
                Lcodes.emitCodeABCK(fi, OP_EQ, b, c, 0, 0);
                break;
            case OPR_LT:
                Lcodes.emitCodeABCK(fi, OP_LT, b, c, 0, 1);
                break;
            case OPR_LE:
                Lcodes.emitCodeABCK(fi, OP_LE, b, c, 0, 1);
                break;
            case OPR_GE:
                Lcodes.emitCodeABCK(fi, OP_LT, b, c, 0, 0);
                break;
            case OPR_GT:
                Lcodes.emitCodeABCK(fi, OP_LE, b, c, 0, 0);
                break;
            default:
                break;
        }
        fi.freeReg(2);
        int jmp = Lcodes.emitCodeJump(fi, 0, 0);
        exprDesc.setInfo(jmp);
        exprDesc.setJump(true);
    }

    public void generate(BinaryExpr binaryExpr, ExprDesc exprDesc) {
        int b = exp2ArgAndKind(fi, binaryExpr.getLeft(), ArgAndKind.ARG_REG).getArg();
        int c = exp2ArgAndKind(fi, binaryExpr.getRight(), ArgAndKind.ARG_REG).getArg();
        switch (binaryExpr.getOp()) {
            case OPR_ADD:
            case OPR_SUB:
            case OPR_MUL:
            case OPR_DIV:
            case OPR_IDIV:
            case OPR_MOD:
            case OPR_POW:
            case OPR_BAND:
            case OPR_BOR:
            case OPR_SHL:
            case OPR_SHR:
                OpCode opCode = OpCode.getOpCode(binaryExpr.getOp().getOp() - OPR_ADD.getOp() + OP_ADD.getCode());
                if (isStatement()) {
                    Lcodes.emitCodeABC(fi, opCode, b, b, c);
                } else {
                    Lcodes.emitCodeABC(fi, opCode, exprDesc.getReg(), b, c);
                }
                break;
        }
        fi.freeReg(2);
    }

    public void generate(VarargExpr expr, ExprDesc exprDesc) {
        Lcodes.emitCodeABC(fi, OpCode.OP_VARARG, exprDesc.getReg(), exprDesc.getN() + 1, 0);
    }

    public void generate(NilExpr expr, ExprDesc exprDesc) {
        Lcodes.emitCodeABC(fi, OpCode.OP_LOADNIL, exprDesc.getReg(), exprDesc.getN() - 1, 0);

    }

    public void generate(TrueExpr expr, ExprDesc exprDesc) {
        Lcodes.emitCodeABC(fi, OpCode.OP_LOADTRUE, exprDesc.getReg(), 0, 0);
    }

    public void generate(FalseExpr expr, ExprDesc exprDesc) {
        Lcodes.emitCodeABC(fi, OpCode.OP_LOADFALSE, exprDesc.getReg(), 0, 0);
    }

    public void generate(FloatExpr expr, ExprDesc exprDesc) {
        int k = fi.indexOfConstant(TValue.doubleValue(expr.getF()));
        Lcodes.emitCodeK(fi, exprDesc.getReg(), k);
    }

    public void generate(IntExpr expr, ExprDesc exprDesc) {
        int k = fi.indexOfConstant(TValue.intValue(expr.getI()));
        Lcodes.emitCodeK(fi, exprDesc.getReg(), k);
    }

    public void generate(StringExpr expr, ExprDesc exprDesc) {
        int k = fi.indexOfConstant(TValue.strValue(expr.getStr()));
        Lcodes.emitCodeK(fi, exprDesc.getReg(), k);
    }

    public ArgAndKind exp2ArgAndKind(FunctionInfo fi, Expr expr, int kind, ExprDesc exprDesc) {
        //去掉无用的嵌套，直接执行里层的表达式

        if (expr instanceof SuffixedExp) {
            SuffixedExp temp = (SuffixedExp) expr;
            if (temp.getSuffixedContent() == null) {
                return exp2ArgAndKind(fi, temp.getPrimaryExr(), kind);
            }
        }
        if ((kind & ArgAndKind.ARG_CONST) > 0) {
            int idx = -1;
            if (expr instanceof NilExpr) {
                idx = fi.indexOfConstant(TValue.nilValue());
            } else if (expr instanceof FalseExpr) {
                idx = fi.indexOfConstant(TValue.falseValue());
            } else if (expr instanceof TrueExpr) {
                idx = fi.indexOfConstant(TValue.trueValue());
            } else if (expr instanceof IntExpr) {
                idx = fi.indexOfConstant(TValue.intValue(((IntExpr) expr).getI()));
            } else if (expr instanceof FloatExpr) {
                idx = fi.indexOfConstant(TValue.doubleValue(((FloatExpr) expr).getF()));
            } else if (expr instanceof StringExpr) {
                idx = fi.indexOfConstant(TValue.strValue(((StringExpr) expr).getStr()));
            }
            if (idx >= 0 && idx <= 0xFF) {
                return new ArgAndKind(idx, ArgAndKind.ARG_CONST);
            }
        }

        if (expr instanceof NameExpr) {
            //从函数的 localVar中查找变量
            if ((kind & ArgAndKind.ARG_REG) != 0) {
                int r = fi.slotOfLocVar(((NameExpr) expr).getName());
                if (r != -1) {
                    return new ArgAndKind(r, ArgAndKind.ARG_REG);
                }
            }
            //从 UpVal中查找变量
            if ((kind & ArgAndKind.ARG_UPVAL) != 0) {
                int r = fi.indexOfUpval(((NameExpr) expr).getName());
                if (r != -1) {
                    return new ArgAndKind(r, ArgAndKind.ARG_UPVAL);
                }
            }
        }
        int a = fi.allocReg();
        if (expr instanceof LogicExpr) {
            generateLogicExpr(expr, createDesc(a, 1));
        } else {
            expr.generate(this, exprDesc != null ? exprDesc : createDesc(a, 1));
        }
        return new ArgAndKind(a, ArgAndKind.ARG_REG);
    }

    /**
     * 将 表达式进行处理，结果存储在 返回的 ArgAndKind对象里面 kind表示，存储的类型，
     */
    public ArgAndKind exp2ArgAndKind(FunctionInfo fi, Expr expr, int kind) {
        return exp2ArgAndKind(fi, expr, kind, null);
    }
    public ExprDesc createDesc(int a){
        ExprDesc exprDesc = new ExprDesc();
        exprDesc.setReg(a);
        return exprDesc;
    }
    public ExprDesc createDesc(int a, int n) {
        ExprDesc exprDesc = new ExprDesc();
        exprDesc.setReg(a);
        exprDesc.setN(n);
        return exprDesc;
    }
}

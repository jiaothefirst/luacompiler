package com.jdy.lua.lcodes;

import com.jdy.lua.lex.LexState;
import com.jdy.lua.lobjects.Proto;
import com.jdy.lua.lobjects.TValue;
import com.jdy.lua.lopcodes.Instruction;
import com.jdy.lua.lopcodes.Instructions;
import com.jdy.lua.lopcodes.OpCode;
import com.jdy.lua.lopcodes.OpMode;
import com.jdy.lua.lparser.*;
import com.jdy.lua.ltm.TMS;

import java.util.List;

import static com.jdy.lua.LuaConstants.*;
import static com.jdy.lua.lcodes.BinOpr.OPR_EQ;
import static com.jdy.lua.lcodes.BinOpr.OPR_NE;
import static com.jdy.lua.lopcodes.Instructions.getOpCode;
import static com.jdy.lua.lopcodes.Instructions.*;
import static com.jdy.lua.lopcodes.OpCode.*;
import static com.jdy.lua.lparser.ExpKind.*;
import static com.jdy.lua.lparser.LParser.luaY_nVarsStack;
import static com.jdy.lua.ltm.TMS.*;

@SuppressWarnings("all")
public class LCodes {

    /**
     * 对于跳转指令，跳转值为-1表示，等待回填具体的 值
     */
    public static final int NO_JUMP = -1;
    /**
     * 最大寄存器数量
     */
    public static final int MAX_REGS = 255;
    /**
     * 无寄存器使用
     */
    public static final int NO_REG = MAX_REGS;

    /**
     * 保存指令的行号信息
     *
     * lineinfos存放的 行号的偏移地址
     * 前一个指令的 绝对行号地址是  previousline
     * 当前指令的绝对行号地址是  line
     * 存储的是 line - previousline
     *
     */
    public static void saveLineInfo(FuncState fs,int line){
        int lineDif = line - fs.getPreviousline();
        fs.getProto().addLineInfo(lineDif);
        fs.setPreviousline(line);
    }
    public static int luaK_code(FuncState fs, Instruction i){
        fs.addInstruction(i);
        saveLineInfo(fs,fs.getLexState().getLastline());
        return fs.getPc() - 1;
    }


    public static int luaK_codeABC(FuncState fs, OpCode o,int a,int b,int c){
        return luaK_code(fs, create_ABCK(o.getCode(),a,b,c,0));
    }
    public static int luaK_codeABCk(FuncState fs, OpCode o,int a,int b,int c, int k){
        return luaK_code(fs, create_ABCK(o.getCode(),a,b,c,k));
    }

    public static int luaK_codeABx(FuncState fs, OpCode o,int a,int bc){
        //无符号的Bx
        return luaK_code(fs,create_ABx(o.getCode(),a,bc));
    }

    public static int luaK_codeAsBx(FuncState fs, OpCode o,int a,int bc){
        //有符号的Bx
        int b = bc + OFFSET_sBx;
        return luaK_code(fs,create_ABx(o.getCode(),a,b));
    }

    /**
     * iAx
     */
    public static int codeExtraArg(FuncState fs,int a){
        return luaK_code(fs,create_Ax(OP_EXTRAARG.getCode(),a));
    }

    /**
     * 生成一个 load constant的指令
     * 如果 indexForTable constants 小于18 bit，使用OP_LOADK
     * 否则就是OP_LOADKX,并且，额外生成一条OP_EXTRAARG指令
     */
    public static int luaK_codeK (FuncState fs, int reg, int k) {
        if (k <= MAX_ARG_Bx)
            return luaK_codeABx(fs, OP_LOADK, reg, k);
        else {
            int p = luaK_codeABx(fs, OP_LOADKX, reg, 0);
            codeExtraArg(fs, k);
            return p;
        }
    }

    /**
     * 检查寄存器 stack的数目
     */
    public static void luaK_checkStack(FuncState fs,int n){
        int newStack = fs.getFreereg() + n;
        if(newStack > fs.getProto().getMaxStackSize()){
            if(newStack > MAX_REGS){
                System.err.println("寄存器达到最大值");
            }
            //重新设置 最大栈的尺寸
            fs.getProto().setMaxStackSize(newStack);
        }
    }

    /**
     * 申请n个寄存器使用
     */
    public static void luaK_reserveRegs(FuncState fs,int n){
        luaK_checkStack(fs,n);
        fs.setFreereg(fs.getFreereg() + n);
    }

    /**
     * 释放寄存器 reg。  如果它既不是 local变量也不是常量索引
     * 不会释放 local变量所占用的 寄存器位置
     */
    public static void freeReg (FuncState fs, int reg) {
        if (reg >= luaY_nVarsStack(fs)) {
            fs.decreFreeReg();
        }
    }

    public static void freeRegs (FuncState fs, int r1, int r2) {
        if (r1 > r2) {
            freeReg(fs, r1);
            freeReg(fs, r2);
        }
        else {
            freeReg(fs, r2);
            freeReg(fs, r1);
        }
    }

    /**
     ** 释放 表达式 e 使用的寄存器
     */
    public static void freeExpReg (FuncState fs, ExpDesc e) {
        if (e.getK() == VNONRELOC){
            freeReg(fs, e.getInfo());
        }
    }


    /**
       释放 表达式 e1,e2使用的表达式
     */
    static void freeexps (FuncState fs, ExpDesc e1, ExpDesc e2) {
        int r1 = (e1.getK() == VNONRELOC) ? e1.getInfo() : -1;
        int r2 = (e2.getK() == VNONRELOC) ? e2.getInfo() : -1;
        freeRegs(fs, r1, r2);
    }

    /**
     * 加入一个 产量到 Proto 的常量数组里面
     * LexState的h表，存储了 常量->常量数组的下标
     */
    public static int addK(FuncState fs,TValue key,TValue value){
        TValue val = new TValue();
        Proto proto = fs.getProto();
        LexState lex = fs.getLexState();
        TValue idx = lex.getH().get(key);
        if(idx != null){
            return (int) idx.getI();
        }
        //创建一个新的常量到常量数组里面
        int k = fs.getNumOfConstants();
        val.setI(k);
        //将值 和 下标，存储
        lex.getH().put(value,val);
        //存储到常量数组里面去
        proto.addConstants(value);
        fs.setNumOfConstants(k + 1);
        return k;
    }

    public static int stringK(FuncState fs, String t){
        TValue v = TValue.strValue(t);
        return addK(fs,v,v);
    }

    public static int luaK_intK(FuncState fs,long n){
        TValue o = TValue.intValue(n);
        return addK(fs,o,o);
    }

    public static int luaK_Number(FuncState fs,double n){
        TValue v = TValue.doubleValue(n);
        return addK(fs,v,v);
    }

    public static int boolF(FuncState fs){
        TValue v = TValue.falseValue();
        return addK(fs,v,v);
    }

    public static int boolT(FuncState fs){
        TValue v = TValue.trueValue();
        return addK(fs,v,v);
    }

    public static int nilT(FuncState fs){
        TValue v = TValue.nilValue();
        return addK(fs,v,v);
    }

    /**
     * 检查 i 能否存储在 sC 操作数上面
     */
    public static boolean fitsC(long i){
        return i + OFFSET_sC < MAX_ARG_C;
    }

    /**
     ** 检查i 能否存储在 sBx 操作数上面
     */
    public static boolean fitsBx (long i) {
        return (-OFFSET_sBx <= i && i <= MAX_ARG_Bx - OFFSET_sBx);
    }

    /**
     * 如果数值大小，能够存储在 Instruction里面， 就用 loadi
     */
    public  static void luaK_int (FuncState fs, int reg, long i) {
        if (fitsBx(i))
            luaK_codeAsBx(fs, OP_LOADI, reg, (int)i);
        else
            luaK_codeK(fs, reg, luaK_intK(fs, i));
    }

    /**
     * 如果数值大小，能够存储在 Instruction里面， 就用 loadf
     */

    public static void luaK_Float(FuncState fs,int reg,double f){
        long i = (long)f;
        //如果浮点数是 10.0 这种，没有尾数的，直接存储整数
        if(Math.floor(f) == i && fitsBx(i)){
            luaK_codeAsBx(fs,OP_LOADF,reg,(int)i);
        } else{
            //当成常量存
            luaK_codeK(fs,reg,luaK_Number(fs,f));
        }
    }

    /**
     * 常量转成 Expdesc
     */
    public static void const2Exp(TValue v,ExpDesc expDesc){
        switch (v.getValueType()) {
            case LUA_TNUMINT:
                expDesc.setK(VKINT);
                expDesc.setIval(v.getI());
                break;
            case LUA_TNUMFLONT:
                expDesc.setK(VKFLT);
                expDesc.setNval(v.getF());
                break;
            case LUA_TFALSE:
                expDesc.setK(VFALSE);
                break;
            case LUA_TTRUE:
                expDesc.setK(VTRUE);
                break;
            case LUA_TNIL:
                expDesc.setK(VNIL);
                break;
            case LUA_TSTRING:
                expDesc.setK(VKSTR);
                expDesc.setStrval((String)v.getObj());
                break;
            default: break;
        }
    }



    public static Instruction getInstruction(FuncState f, ExpDesc expDesc){
        Proto proto = f.getProto();
        return proto.getInstruction(expDesc.getInfo());
    }

    public static boolean hasJumps(ExpDesc desc){
        return desc.getTJmp() != desc.getFJmp();
    }

    /**
     * 将 表达式 转成 numeric，如果可以的话
     */
    public static boolean tonumeral (ExpDesc e, TValue v) {
        if (hasJumps(e))
            return false;  /* not a numeral */
        switch (e.getK()) {
            case VKINT:
                if (v != null){
                    v.setI(e.getIval());
                    v.setValueType(LUA_TNUMINT);
                }
                return true;
            case VKFLT:
                if (v != null) {
                    v.setF(e.getNval());
                    v.setValueType(LUA_TNUMFLONT);
                }
                return true;
            default: return false;
        }
    }

    /**
     * 根据 常量表达式 获取 常量
     */
    public static TValue const2Val(FuncState fs,ExpDesc desc){
        DynData dynData = fs.getLexState().getDyd();
        List<Vardesc> vardescList = dynData.getArr();
        if(desc.getK() == ExpKind.VCONST){
            return vardescList.get(desc.getInfo()).getK();
        }
        return null;
    }

    /**
     *如果表达式是常量，填充到v，返回true
     */
    public static boolean luaK_Exp2Const(FuncState fs, ExpDesc desc, TValue v){
        if(hasJumps(desc)){
            return false;
        }
        switch (desc.getK()){
            case VFALSE:v.setValueType(LUA_TFALSE);break;
            case VTRUE:v.setValueType(LUA_TTRUE);break;
            case VNIL:v.setValueType(LUA_TNIL);break;
            case VKSTR:v.setValueType(LUA_TSTRING);v.setObj(desc.getStrval());break;
            case VCONST:v.setFromTValue(const2Val(fs,desc));break;
            default:return tonumeral(desc,v);
        }
        return true;
    }

    /**
     * 设置函数 调用 返回值的数量 或者 指令OP_VARARG中 可变参数参数值的数量
     */
    public static  void luaK_setReturns(FuncState fs,ExpDesc e,int nResults){
        Instruction pc = getInstruction(fs,e);
        // 函数调用
        if(e.getK() == VCALL){
            setArgC(pc, nResults + 1);
        } else{
            // VVararg
            setArgC(pc,nResults + 1);
            setArgA(pc,fs.getFreereg());
        }
    }

    /**
     * 字符串表达式，转常量；
     * info存储，字符串常量的下标
     */
    public static void str2K(FuncState fs, ExpDesc e){
        e.setInfo(stringK(fs,e.getStrval()));
        e.setK(VK);
    }
    /**
     * 修正 返回一个结果的表达式；
     * 如果表达式不是一个多返回值的结果，就无需处理
     */
    public static void luaK_setOneRet(FuncState fs,ExpDesc e){
        if(e.getK() == VCALL){
            //函数调用会变成 VNONRELOC,表示函数调用的结果需要存放在固定的寄存器里面
            e.setK(VNONRELOC);
            e.setInfo(getArgA(getInstruction(fs,e)));
        } else if(e.getK()== VVARARG){
            setArgC(getInstruction(fs,e),2);
            //表达式的结果可以存放在任意的寄存器里
            e.setK(VRELOC);
        }
    }

    /**
     * 确保 表达式 e 不在是一个 variable
     *
     * 设置了VRELOC类型的表达式 并没有 给 参数 a 赋值， 表达式的值所存储的寄存器是在 discharge2reg设置的
     *
     * 没有处理 跳转指令
     */
    public static void luaK_dischargeVars(FuncState fs,ExpDesc e){
        switch (e.getK()){
            case VCONST: {
                const2Exp(const2Val(fs, e), e);
                break;
            }
            /**
             * local 变量已经存放在寄存器里了
             */
            case VLOCAL: {
                e.setInfo(e.getRegisterIndex());
                e.setK(VNONRELOC);  /* becomes a non-relocatable value */
                break;
            }
            case VUPVAL: {  /* move value to some (pending) register */
                e.setInfo(luaK_codeABC(fs, OP_GETUPVAL, 0,e.getInfo(), 0));
                e.setK(VRELOC);
                break;
            }
            case VINDEXUP: {
                e.setInfo(luaK_codeABC(fs, OP_GETTABUP, 0, e.getTable(), e.getIndexForTable()));
                e.setK(VRELOC);
                break;
            }
            case VINDEXI: {
                //释放寄存器，因为寄存器里面的值，已经包含在下面生成的指令里面了,下面的freeReg同理
                freeReg(fs, e.getTable());
                e.setInfo(luaK_codeABC(fs, OP_GETI, 0, e.getTable(), e.getIndexForTable()));
                e.setK(VRELOC);
                break;
            }
            case VINDEXSTR: {
                freeReg(fs, e.getTable());
                e.setInfo(luaK_codeABC(fs, OP_GETFIELD, 0, e.getTable(), e.getIndexForTable()));
                e.setK(VRELOC);
                break;
            }
            case VINDEXED: {
                freeRegs(fs,e.getTable(),e.getIndexForTable());
                e.setInfo(luaK_codeABC(fs, OP_GETTABLE, 0, e.getTable(),e.getIndexForTable()));
                e.setK(VRELOC);
                break;
            }
            case VVARARG: case VCALL: {
                luaK_setOneRet(fs,e);
                break;
            }
            default: break;
        }

    }
    /**
     * 确保表达式的 值存放在 reg寄存器里面，让表达式类型变成 VNONRELOC
     *
     * 没有处理 跳转指令
     */
    public static void discharge2reg(FuncState fs,ExpDesc e,int reg){
        //初步对表达式进行调整
        luaK_dischargeVars(fs,e);
        switch (e.getK()) {
            case VNIL: {
                luaK_Nil(fs, reg, 1);
                break;
            }
            case VFALSE: {
                luaK_codeABC(fs, OP_LOADFALSE, reg, 0, 0);
                break;
            }
            case VTRUE: {
                luaK_codeABC(fs, OP_LOADTRUE, reg, 0, 0);
                break;
            }
            case VKSTR: {
                str2K(fs, e);
            }  /* FALLTHROUGH */
            case VK: {
                luaK_codeK(fs, reg,e.getInfo());
                break;
            }
            case VKFLT: {
                luaK_Float(fs, reg, e.getNval());
                break;
            }
            case VKINT: {
                luaK_int(fs, reg, e.getIval());
                break;
            }
            case VRELOC: {
                Instruction pc = getInstruction(fs, e);
                setArgA(pc, reg);  /* instruction will put result in 'reg' */
                break;
            }
            case VNONRELOC: {
                //通过move指令，将值赋值给指定的寄存器
                if (reg != e.getInfo())
                    luaK_codeABC(fs, OP_MOVE, reg, e.getInfo(), 0);
                break;
            }
            default: {
                return;  /* nothing to do... */
            }
        }
        e.setInfo(reg);
        e.setK(VNONRELOC);
    }

    /**
     * 表达式存放到任意一个寄存器里面去
     *
     * 没有处理 跳转指令
     */
    public static void discharge2AnyReg (FuncState fs,ExpDesc e) {
        if (e.getK() != VNONRELOC) {  /* no fixed register yet? */
            luaK_reserveRegs(fs, 1);  /* get a register */
            discharge2reg(fs, e, fs.getFreereg() - 1);  /* put value there */
        }
    }

    public static int code_Loadbool (FuncState fs, int A, OpCode op) {
        //生成一个跳转标签，可能会有jmp语句跳转到这
        luaK_GetLabel(fs);
        return luaK_codeABC(fs, op, A, 0, 0);
    }

    /**
     *  不是 OP_TESTSET就会返回true，应该是因为 OP_TESETSET已经将值存储在了寄存器里，所以不需要再存储了
     */
    public static boolean needValue(FuncState fs,int list){
        for(;list != NO_JUMP;list = getJumpDestination(fs,list)){
            Instruction i = getJumpCongtrol(fs,list);
            if(getOpCode(i) != OP_TESTSET){
                return true;
            }
        }
        return false;
    }
    /**
     * 表达式的值存放到寄存器里面去
     */
    public static void exp2Reg(FuncState fs,ExpDesc e,int reg){
        discharge2reg(fs,e,reg);
        if(e.getK() == VJMP){
            luaK_Concat(fs,e,e.getInfo(),true);
        }
        if(hasJumps(e)){
            //整个表达式结束后的位置
            int finalPos;
            int pf = NO_JUMP;
            int pT = NO_JUMP;
            if(needValue(fs,e.getTJmp()) || needValue(fs,e.getFJmp())){
                // e如果不是 VJMP类型的，就生成一条jmp指令，用于跳出下面的bool语句
                /**
                 * 例如:   c = a > b  and a  表达式包含了布尔表达式也包含了a
                 *  如果 a > b  则 c = a
                 *  否则 c = false
                 *  一个是布尔值，一个是赋值
                 *  字节码大致如下
                 * 1  LT a b  [ a > b 就执行3， a < b就执行 5]
                 * 2  JMP
                 * 3  MOVE  [执行到这里说明 a > b，此时赋值 c == a，然后跳到7，结束]
                 * 4   JMP [跳转到 7的位置]
                 * 5 OP_LFALSESKIP
                 * 6 OP_LOADTRUE
                 * 7  结尾
                 */
                // 当类似上面注释的情况时， fj会指向一个jmp指令，用于跳过下面的两条语句
                int fj = e.getK() == VJMP ? NO_JUMP :luaK_Jump(fs);
                pf = code_Loadbool(fs, reg, OP_LFALSESKIP);  /* skip next inst. */
                pT = code_Loadbool(fs, reg, OP_LOADTRUE);
                //如果e 不是一个 test 类型的指令，跳过， 上面生成的指令
                luaK_patchToHere(fs,fj);
            }
            finalPos = luaK_GetLabel(fs);
            //回填，真假出口， TESTSET 使用作为 target， 其他测试指令使用 pf/pf作为target
            patchListAux(fs,e.getFJmp(),finalPos,reg,pf);
            patchListAux(fs,e.getTJmp(),finalPos,reg,pT);
        }
        e.setFJmp(NO_JUMP);
        e.setTJmp(NO_JUMP);
        e.setInfo(reg);
        e.setK(VNONRELOC);
    }

    /**
     * 表达式存储到下一个可用的寄存器里面
     */
    public static void luaK_exp2nextReg (FuncState fs, ExpDesc e) {
        luaK_dischargeVars(fs, e);
        freeExpReg(fs, e);
        //申请一个free的寄存器
        luaK_reserveRegs(fs, 1);
        exp2Reg(fs, e, fs.getFreereg() - 1);
    }

    /**
       e存储到 register，返回reg下标
     */
    public static int luaK_exp2anyreg (FuncState fs, ExpDesc e) {
        luaK_dischargeVars(fs, e);
        if (e.getK() == VNONRELOC) {  /* expression already has a register? */
            if (!hasJumps(e))  /* no jumps? */
                return e.getInfo();  /* result is already in a register */
            if (e.getInfo() >= luaY_nVarsStack(fs)) {  /* reg. is not a local? */
                exp2Reg(fs, e, e.getInfo());  /* put final result in it */
                return e.getInfo();
            }
        }
        luaK_exp2nextReg(fs, e);  /* default: use next available register */
        return e.getInfo();
    }


    /*
     ** Ensures final expression result is either in a register
     ** or in an upvalue.
     */
   public static void luaK_exp2anyregup (FuncState fs, ExpDesc e) {
        if (e.getK() != VUPVAL || hasJumps(e))
            luaK_exp2anyreg(fs, e);
    }


    /*
     ** Ensures final expression result is either in a register
     ** or it is a constant.
     */
    public static void luaK_exp2val (FuncState fs, ExpDesc e) {
        if (hasJumps(e))
            luaK_exp2anyreg(fs, e);
        else
            luaK_dischargeVars(fs, e);
    }
    /**
     *  尝试将表达式转成常量， info存储常量下标
     * */
    public static boolean luaK_exp2K (FuncState fs, ExpDesc e) {
        if (!hasJumps(e)) {
            int info;
            switch (e.getK()) {
                case VTRUE: info = boolT(fs); break;
                case VFALSE: info = boolF(fs); break;
                case VNIL: info = nilT(fs); break;
                case VKINT: info = luaK_intK(fs, e.getIval()); break;
                case VKFLT: info = luaK_Number(fs, e.getNval()); break;
                case VKSTR: info = stringK(fs, e.getStrval()); break;
                case VK: info =e.getInfo(); break;
                default: return false;  /* not a constant */
            }
            if (info <= MAX_INDEX_RK) {  /* does constant fit in 'argC'? */
               e.setK(VK);  /* make expression a 'K' expression */
               e.setInfo(info);
               return true;
            }
        }
        /* else, expression doesn'currTk fit; leave it unchanged */
        return false;
    }

    /**
     * 表达式 的结果 时 一个 常量索引或者寄存器索引
     */
    public static int luaK_exp2RK (FuncState fs, ExpDesc e) {
        if (luaK_exp2K(fs, e))
            return 1;
        luaK_exp2anyreg(fs, e);
        return 0;

    }


    public static void codeABRK (FuncState fs, OpCode o, int a, int b,
                          ExpDesc ec) {
        int k = luaK_exp2RK(fs, ec);
        luaK_codeABCk(fs, o, a, b, ec.getInfo(), k);
    }

    /**
     *  生成code存储表达式 ex的result 到   var上面
     *   对于t[a] = x
     *   来说  currTk[a]就是 var;  x 就是ex
     */
    public static void luaK_storevar (FuncState fs,ExpDesc var, ExpDesc ex) {
        switch (var.getK()) {
            case VLOCAL: {
                freeExpReg(fs, ex);
                //将表达式 ex的值存到 register idx上面
                exp2Reg(fs, ex, var.getRegisterIndex());
                return;
            }
            case VUPVAL: {
                int e = luaK_exp2anyreg(fs, ex);
                // var.getInfo()记录了 VUPVAl的下标
                luaK_codeABC(fs, OP_SETUPVAL, e, var.getInfo(), 0);
                break;
            }
            case VINDEXUP: {
                //var.getTable()记录了 table，var.getIndexForTable()记录了key的索引
                codeABRK(fs, OP_SETTABUP, var.getTable(), var.getIndexForTable(), ex);
                break;
            }
            case VINDEXI: {
                //var.getTable()记录了 table，var.getIndexForTable()记录了key的索引
                codeABRK(fs, OP_SETI, var.getTable(), var.getIndexForTable(), ex);
                break;
            }
            case VINDEXSTR: {
                codeABRK(fs, OP_SETFIELD, var.getTable(), var.getIndexForTable(), ex);
                break;
            }
            case VINDEXED: {
                codeABRK(fs, OP_SETTABLE, var.getTable(), var.getIndexForTable(), ex);
                break;
            }
            default: break;  /* invalid var kind to store */
        }
        freeExpReg(fs, ex);
    }

    /**
     **处理self  指令(convert expression 'e' into 'e:key(e,').
     *
     * a有 方法 B
     *
     * 定义方式1： 用点分割，需要手动写self，作为参数
     *   function a.B(self)
     *   end
     *   调用时需要手动传a
     *   a.B(a);
     * 定义方式2： 用 :分割，自带self参数
     *  function a:B
     *
     *  end
     *  调用时,不用手动传
     *  a:B()
     *
     */
    public static void luaK_self (FuncState fs, ExpDesc e, ExpDesc key) {
        int ereg;
        luaK_exp2anyreg(fs, e);
        ereg = e.getInfo();  /* register where 'e' was placed */
        freeExpReg(fs, e);
        e.setInfo(fs.getFreereg());/* base register for op_self */
        e.setK(VNONRELOC);  /* self expression has a fixed register */
        luaK_reserveRegs(fs, 2);  /* function and 'self' produced by op_self ,self 作为参数传入 */
        // OP_SELF， 会在R[A] 存放a, R[A+1] 存放方法B
        codeABRK(fs, OP_SELF, e.getInfo(), ereg, key);
        freeExpReg(fs, key);
    }

    /**
     ** 反转condition 'e' (where 'e' is a comparison).
     * 将 k反转就能让 跳转条件反转
     */
    public static void negateCondition (FuncState fs, ExpDesc e) {
        Instruction pc = getJumpCongtrol(fs, e.getInfo());
        setArgk (pc,getArgk(pc) ^ 1);
    }

    /**
     *
     * jump if 'e' is 'cond'
     *
     * 如果 e 为true， 且 cond 为true 才会jump
     * 如果 e 为false， cond 为false 也会jump
     *
     * jump 由 e 和 cond共同决定
     */
    static int jumponCond (FuncState fs,ExpDesc e, boolean cond) {
        if (e.getK() == VRELOC) {
            Instruction ie = getInstruction(fs, e);
            //如果前个指令为 not， 进行优化，反转cond即可
            if (getOpCode(ie) == OP_NOT) {
                removeLastInstruction(fs);  /* remove previous OP_NOT */
                return condJump(fs, OP_TEST, getArgB(ie), 0, 0, !cond ? 1 : 0);
            }
            /* else go through */
        }
        discharge2AnyReg(fs, e);
        freeExpReg(fs, e);
        return condJump(fs, OP_TESTSET, NO_REG, e.getInfo(), 0, cond ? 1 : 0);
    }

    /**
     * goIfTrue，理解为 true时，go through 继续执行， 为false时跳出
     *  注：每条指令执行时，pc指向下一条指向
     *  对于下列语句
     *   if a == b and b == c
     *     xxx
 *       else
     *     xxx
     *   end
     *
     *   Eq a b
     *   jmp
     *   Eq b c
     *   jmp
     *   true 时执行的内容
     *   jmp 到结尾
     *   false 时执行的内容
     *
     *如果 Eq a b 为 true，pc++就要接着执行 Eq b c，如果为true，pc++ 执行true时的内容
     *
     * 所以 and 场景用 luaK_goIfTrue
     *
     * 需要处理 假出口； 真的继续执行即可
     */
    public static void luaK_goIfTrue (FuncState fs, ExpDesc e) {
        int pc;  /* pc of new jump */
        luaK_dischargeVars(fs, e);
        switch (e.getK()) {
            case VJMP: {  /* condition? */
                //颠倒跳转条件，将标记为k反转即可
                // 测试语句都是 if(e ~= constants) pc++;  eq生成的指令是反过来的，因为k=1，这里进行修正。
                negateCondition(fs, e);  /* jump when it is false */
                pc = e.getInfo();  /* save jump position */
                break;
            }
            //如果表达式，是一个常量，不需要跳转指令，直接接着执行
            case VK: case VKFLT: case VKINT: case VKSTR: case VTRUE: {
                pc = NO_JUMP;  /* always true; do nothing */
                break;
            }
            default: {
                //如果是 VTFALSE，则需要直接跳转到 false的执行处
                pc = jumponCond(fs, e, false);  /* jump when false */
                break;
            }
        }
        //pc是假出口，连接 e->fJmp 和 pc
        luaK_Concat(fs,e,pc,false);  /* insert new jump in false list */
        // 回填真出口到当前位置，真出口 jump到这里
        luaK_patchToHere(fs,e.getTJmp());  /* true list jumps to here (to go through) */
        e.setTJmp(NO_JUMP);
    }

    /**
     * goIfFalse理解为 false时继续执行，为true时，跳到 true的执行内容
     * 需要处理 真出口， 假的继续执行
     */

    public static void luaK_goIfFalse (FuncState fs, ExpDesc e) {
        int pc;  /* pc of new jump */
        luaK_dischargeVars(fs, e);
        switch (e.getK()) {
            case VJMP: {
                //本来就是反的，不用修正
                pc = e.getInfo();  /* already jump if true */
                break;
            }
            case VNIL: case VFALSE: {
                pc = NO_JUMP;  /* always false; do nothing   如果是false，需要继续执行*/
                break;
            }
            default: {
                pc = jumponCond(fs, e, true);  /* jump if true */
                break;
            }
        }
        //拼接真出口
        luaK_Concat(fs,e,pc,true);/* insert new jump in 't' list */
        //处理假出口
        luaK_patchToHere(fs, e.getFJmp());  /* false list jumps to here (to go through) */
        e.setFJmp(NO_JUMP);
    }
    /**
     * not 运算
     */
    public static void codeNot(FuncState fs,ExpDesc e){
        switch (e.getK()){
            //nil，false直接反转即可
            case VNIL:case VFALSE:
                e.setK(VTRUE);
            // not 'x' , not 1 ,not 0.5 ,not true
            case VK:case VKFLT:case VKINT:case VKSTR:case VTRUE:
                e.setK(VFALSE);
                break;
            //jmp语句，反转跳转条件即可
            case VJMP:
                negateCondition(fs,e);
                break;
            case VRELOC:case VNONRELOC:{
                //表达式的值存储到寄存器里面
                discharge2AnyReg(fs,e);
                freeExpReg(fs,e);
                e.setInfo(luaK_codeABC(fs,OP_NOT,0,e.getInfo(),0));
                e.setK(VRELOC);

            }
            default:break;
        }
        //真假出口链表交换
        int t = e.getTJmp();
        int f = e.getFJmp();
        e.setFJmp(t);
        e.setTJmp(f);
        //not 中，表达式的值是无用的，将 TESTSET 跳转成 TEST
        removeValues(fs,e.getFJmp());
        removeValues(fs,e.getTJmp());
    }

    /**
     *返回 e 是不是字符串常量
     */
    public static boolean isKstr(FuncState fs,ExpDesc e){
        Proto proto = fs.getProto();
        List<TValue> ks = proto.getConstants();
        return e.getK() == VK
                && !hasJumps(e)
                && e.getInfo() <= MAX_ARG_B && e.getInfo() < ks.size()
                && ks.get(e.getInfo()).getValueType() == LUA_TSTRING;
    }
    /**
     * 返回 e 是不是 数字常量
     */
    public static boolean luaK_isKint(ExpDesc e){
        return e.getK() == VKINT && !hasJumps(e);
    }
    /**
     * 检查 e 是不是能放进 参数c的整数
     */
    public static boolean isCint(ExpDesc e){
        return luaK_isKint(e) && e.getIval() <= MAX_ARG_C;
    }

    /**
     * 检查 e 是不是能放进 参数c的整数
     */
    public static boolean isSCint(ExpDesc e){
        return luaK_isKint(e) && fitsC(e.getIval());
    }

    /**
     * 检查 e 是不是 能放进 sB or sC的 整数/浮点数
     */
    public static boolean isScNumber(ExpDesc e,NumChecker checker){
        long i;
        if(e.getK() == VKINT){
            i = e.getIval();
        } else if(e.getK() == VKFLT){
            checker.isFloat = 1;
            i = (long)e.getNval();
        } else {
            //不是数字
            return false;
        }
        if(!hasJumps(e) && fitsC(i)){
            checker.val = int2Sc((int)i);
            return true;
        }
        return false;
    }
    /**
     * 创建表达式 currTk[constants]
     * t的值需要存放在一个寄存器或者 upvalue
     * Upvalues的key 应该存放在寄存器中 或者常量表中
     */
    public static void luaK_Indexed(FuncState fs,ExpDesc t,ExpDesc k){
        if(k.getK() == VKSTR){
            str2K(fs,k);
        }
        //upvalue的索引值不是 常量字符串，就将其放到寄存器里面
        if(t.getK() == VUPVAL && !isKstr(fs,k)){
            luaK_exp2anyreg(fs,t);
        }
        //处理 VUPVal
        if(t.getK() == VUPVAL){
            // upvalue indexForTable
            t.setTable(t.getInfo());
            //索引 upvalue的字面量
            t.setIndexForTable(k.getInfo());
            t.setK(VINDEXUP);
        } else{
            //处理table
            //table的寄存器索引
            t.setTable(t.getK() == VLOCAL ? t.getRegisterIndex() : t.getInfo());
            //字符串常量索引table
            if(isKstr(fs,k)){
                t.setIndexForTable(k.getInfo());
                t.setK(VINDEXSTR);
            } else if(isCint(k)){
                //整数常量索引table
                t.setIndexForTable((int)k.getIval());
                t.setK(VINDEXI);
            } else{
                //寄存器索引table
                t.setIndexForTable(luaK_exp2anyreg(fs,k));
                t.setK(VINDEXED);
            }
        }
    }

    /**
     * 校验 操作符 操作数
     */
    public static boolean validateOp(int op,TValue v1, TValue v2){
        if(!isNumber(v1) || !isNumber(v2)){
            return false;
        }
        switch (op){
            case LUA_OPDIV: case LUA_OPIDIV: case LUA_OPMOD:
                return intV(v2) != 0;
        }
        return true;
    }

    /**
     * 整数的运算
     */
    private static void calInteger(BinOpr op,long a,long b,TValue res){
        res.setValueType(LUA_TNUMINT);
        switch (op){
            case OPR_BAND: res.setI(a & b);break;
            case OPR_BOR:res.setI(a | b);break;
            case OPR_BNOT:res.setI(a ^ b);break;
            case OPR_SHL: res.setI(a << b);break;
            case OPR_SHR: res.setI(a >> b);break;
            case OPR_IDIV: res.setI(a/b);break;
            case OPR_MOD: res.setI(a % b);break;
            default:break;
        }
    }
    /** double 数值的运算
     * */
    public static void calDouble(BinOpr op,TValue v1,TValue v2,TValue res){
        double num1 = v1.getValueType() == LUA_TNUMFLONT ? v1.getF() : v1.getI();
        double num2 = v2.getValueType() == LUA_TNUMFLONT ? v2.getF() : v2.getI();
        double result = 0.0;
        switch (op){
            case OPR_ADD:result = num1+num2;break;
                case OPR_SUB: result = num1-num2;break;
                case OPR_MUL: result = num1*num2;break;
                case OPR_DIV: result = num1/num2;break;
                case OPR_POW: result = Math.pow(num1,num2);break;
                default:break;
        }
        if(v1.getValueType() == LUA_TNUMFLONT || v2.getValueType() == LUA_TNUMFLONT){
            res.setValueType(LUA_TNUMFLONT);
            res.setF(result);
        } else{
            res.setValueType(LUA_TNUMINT);
            res.setI((long)result);
        }
    }
    /**
     * 进行运算
     */
    private static void arith(int op, TValue v1,TValue v2,TValue res){
        BinOpr o =BinOpr.getBinOpr(op);
        res.setValueType(LUA_TNUMINT);
        switch (o){
            case OPR_BAND: case OPR_BOR: case OPR_BNOT:
            case OPR_SHL: case OPR_SHR: case OPR_IDIV:
                long num1 = v1.getI();
                long num2 = v2.getI();
                calInteger(o,num1,num2,res);
                break;
            case OPR_ADD:case OPR_SUB:case OPR_MUL:case OPR_DIV:case OPR_POW:
                calDouble(o,v1,v2,res);
                break;
                default:break;
        }

    }

    /**
     * 常量折叠
     *   如果成功， e1 存放最终的结果
     */
    public static boolean constFolding(FuncState fs,int op,ExpDesc e1,ExpDesc e2){
        TValue v1 = new TValue(),v2 = new TValue(),res = new TValue();
        if(!tonumeral(e1,v1) || !tonumeral(e2,v2) || !validateOp(op,v1,v2)){
            return false;
        }
        //进行运算
        arith(op, v1, v2, res);
        if(isInteger(res)){
            e1.setK(VKINT);
            e1.setIval(res.getI());
        }else{
            double d = res.getF();
            if(d == 0){
                return false;
            }
            e1.setK(VKFLT);
            e1.setNval(d);
        }
        return true;
    }

    /**
     * 处理单目运算符，生成code
     */
    public static void codeUnaryExpVal(FuncState fs,OpCode op,ExpDesc e,int line){
        int r = luaK_exp2anyreg(fs,e);
        freeExpReg(fs,e);
        e.setInfo(luaK_codeABC(fs,op,0,r,0));
        e.setK(VRELOC);
        luaK_fixline(fs,line);
    }

    /**
     * 处理 双目运算符 除了 and,or比较运算符 ,生成code
     */
    public static void finishBinaryExpVal(FuncState fs, ExpDesc e1, ExpDesc e2, OpCode op, int v2, int flip, int line, OpCode mmop, TMS event){
        int v1 = luaK_exp2anyreg(fs,e1);
        int pc = luaK_codeABCk(fs,op,0,v1,v2,0);
        freeexps(fs,e1,e2);
        e1.setInfo(pc);
        e1.setK(VRELOC);
        luaK_fixline(fs,line);
        // call metamethod
        luaK_codeABCk(fs,mmop,v1,v2,event.getT(),flip);
        luaK_fixline(fs,line);
    }

    /**
     处理 双目运算符
     */
    static void codeBinaryExpVal (FuncState fs, OpCode op,
                               ExpDesc e1, ExpDesc e2, int line) {
        int v2 = luaK_exp2anyreg(fs, e2);  /* both operands are in registers */
        finishBinaryExpVal(fs, e1, e2, op, v2, 0, line, OP_MMBIN,TMS.getTMS(op.getCode() - (OP_ADD.getCode()) + TMS.TM_ADD.getT()));
    }

    /**
     * 生成 立即数的 双目运算 code
     */
    public static void codeBinaryImediate(FuncState fs,OpCode op,ExpDesc e1,ExpDesc e2,int flip,int line,TMS event){
        int v2 = int2Sc((int)e2.getIval());
        finishBinaryExpVal(fs,e1,e2,op,v2,flip,line,OP_MMBINI,event);
    }

    /**
     *  生成 二元操作符，移除第二个操作数，保留原有的value；
     *  用于 metamethod
     */
    public static boolean finishBinaryExpNegating(FuncState fs,ExpDesc e1,ExpDesc e2,OpCode op,int line,TMS event){
        if(!luaK_isKint(e2)){
            return false;
        }
        int i2 = (int)e2.getIval();
        if(!(fitsC(i2) && fitsC(-i2))){
            return false;
        }
        finishBinaryExpVal(fs,e1,e2,op,int2Sc(-2),0,line,OP_MMBINI,event);
        setArgB(fs.getProto().getInstruction(fs.getPc() - 1),int2Sc((i2)));
        return true;
    }

    /**
     * 交换表达式
     */
    public static void  swapExps(ExpDesc e1,ExpDesc e2){
        ExpDesc e1Clone = (ExpDesc)e1.clone();
        ExpDesc e2Clone = (ExpDesc)e1.clone();
        e1.setFromExp(e2Clone);
        e2.setFromExp(e1Clone);
    }

    /**
        生成算数运算的code
      */
    public static void codeArith(FuncState fs,BinOpr opr,ExpDesc e1,ExpDesc e2,int flip,int line){
        TMS event = TMS.getTMS(opr.getOp() + TMS.TM_ADD.getT());
        //常量操作数
        if(tonumeral(e2,null) && luaK_exp2K(fs,e2)){
            int v2 =e2.getInfo();
            // 获取常量运算的 OpCode
            OpCode op = OpCode.getOpCode(opr.getOp() + OP_ADDK.getCode());
            finishBinaryExpVal(fs,e1,e2,op,v2,flip,line,OP_MMBINI,event);
        } else{
            //获取正常的OpCode
            OpCode op = OpCode.getOpCode(opr.getOp() + OP_ADD.getCode());
            if(flip == 1){
                swapExps(e1,e2);
            }
            //使用 标准的运算
            codeBinaryExpVal(fs,op,e1,e2,line);
        }
    }

    /**
     * 如果第一个操作数是 常量， 交换顺序，优化成常量相关的运算
     */
    public static void codeCommutative(FuncState fs,BinOpr opr,ExpDesc e1,ExpDesc e2,int line){
        int flip = 0;
        if(tonumeral(e1,null)){
            swapExps(e1,e2);
            flip = 1;
        }
        //转成立即数运算
        if(opr == BinOpr.OPR_ADD && isSCint(e2)){
            codeBinaryImediate(fs,OP_ADDI,e1,e2,flip,line,TMS.TM_ADD);
        } else{
            codeArith(fs,opr,e1,e2,flip,line);
        }
    }

    /**
     * 生成位运算指令
     */
    public static void codeBitWise(FuncState fs,BinOpr opr,ExpDesc e1,ExpDesc e2,int line){
        int flip = 0;
        int v2;
        OpCode op;
        //第一个操作数是常量，需要进行交换
        if(e1.getK() == VKINT && luaK_exp2RK(fs,e1) == 1){
            swapExps(e1,e2);
            flip = 1;
        } else if(!(e2.getK() == VKINT && luaK_exp2RK(fs,e2) == 1)){
            //没有常量，全存放在寄存器里面
            op = OpCode.getOpCode(opr.getOp() + OP_ADD.getCode());
            codeBinaryExpVal(fs,op,e1,e2,line);
            return;
        }
        v2 = e2.getInfo();
        //至少有一个操作数是常量
        op = OpCode.getOpCode(opr.getOp() + OP_ADDK.getCode());
        finishBinaryExpVal(fs,e1,e2,op,v2,flip,line,OP_MMBINK,TMS.getTMS(opr.getOp() + TMS.TM_ADD.getT()));
    }

    /**
     * 生成比较运算的code， 当使用了 立即数作为操作数，isfloat标识是不是浮点数

     */
    public static void codeOrder(FuncState fs,OpCode op,ExpDesc e1,ExpDesc e2){
        int r1,r2;
         NumChecker checker = new NumChecker();
        if(isScNumber(e2,checker)){
            r1 = luaK_exp2anyreg(fs,e1);
            r2 = checker.val;
            op = OpCode.getOpCode(op.getCode() - OP_LT.getCode() + OP_LTI.getCode());
        } else if(isScNumber(e2,checker)){
            /* 反转运算顺序 (A < B) to (B > A) and (A <= B) to (B >= A) */
            r1 = luaK_exp2anyreg(fs,e2);
            r2 = checker.val;
            op = (op == OP_LT) ? OP_GTI : OP_GEI;
        } else{
            //无立即数，运算数都在寄存器里面
            r1 = luaK_exp2anyreg(fs,e1);
            r2 = luaK_exp2anyreg(fs,e2);
        }
        freeexps(fs,e1,e2);
        e1.setInfo(condJump(fs,op,r1,r2,checker.isFloat,1));
        e1.setK(VJMP);
    }

    /**
     * 生成 == ~= 的code，
     * e1 已经存放在了 R/K中 by ‘luaK_infix'
     */
    public static void codeEq(FuncState fs,BinOpr opr,ExpDesc e1,ExpDesc e2){
        int r1,r2;
        NumChecker checker  = new NumChecker();
        OpCode op;
        if(e1.getK() != VNONRELOC){
            swapExps(e1,e2);
        }
        //第一个参数要放在寄存器里面（可能已经放在里面了）
        r1 = luaK_exp2anyreg(fs,e1);
        if(isScNumber(e1,checker)){
            op = OP_EQI;
            //立即数比较
            r2 = checker.val;
        } else if(luaK_exp2RK(fs,e2) == 1){
            op = OP_EQK;
            //常量比较
            r2 = e2.getInfo();
        } else{
            op = OP_EQ;
            r2 = luaK_exp2anyreg(fs,e2);
        }
        freeexps(fs,e1,e2);
        e1.setInfo(condJump(fs,op,r1,r2,checker.isFloat,opr == OPR_EQ ? 1 : 0));
        e1.setK(VJMP);
    }

    /**
     * 处理 前缀 但操作符
     * @param fs
     * @param op
     * @param expDesc
     * @param line
     */
    public static void luaK_Prefix(FuncState fs,UnOpr op,ExpDesc expDesc,int line){
        ExpDesc ef = new ExpDesc();
        ef.setK(VKINT);
        luaK_dischargeVars(fs,expDesc);
        switch (op){
            case OPR_MINUS:case OPR_BNOT:
                //ef作为一个假参数， 尝试常量 折叠
                if(constFolding(fs,op.getOp() + LUA_OPUNM,expDesc,ef)){
                    break;
                }
            case OPR_LEN:
                codeUnaryExpVal(fs,OpCode.getOpCode(op.getOp() + OP_UNM.getCode()),expDesc,line);
                break;
            case OPR_NOT:
                codeNot(fs,expDesc);
                break;
            default:break;
        }
    }

    /**
     * 读取第二个操作数之前 处理第一个i操作数
     * @param fs
     * @param line
     */
    public static void luaK_Infix(FuncState fs,BinOpr op,ExpDesc v) {
        //处理表达式的值，保证不再是一个variable
        luaK_dischargeVars(fs, v);
        switch (op) {
            case OPR_AND: {
                luaK_goIfTrue(fs, v);  /* go ahead only if 'v' is true */
                break;
            }
            case OPR_OR: {
                luaK_goIfFalse(fs, v);  /* go ahead only if 'v' is false */
                break;
            }
            case OPR_CONCAT: {
                luaK_exp2nextReg(fs, v);  /* operand must be on the stack */
                break;
            }
            case OPR_ADD:
            case OPR_SUB:
            case OPR_MUL:
            case OPR_DIV:
            case OPR_IDIV:
            case OPR_MOD:
            case OPR_POW:
            case OPR_BAND:
            case OPR_BOR:
            case OPR_BNOT:
            case OPR_SHL:
            case OPR_SHR: {
                if (!tonumeral(v, null))
                    luaK_exp2anyreg(fs, v);
                /* else keep numeral, which may be folded with 2nd operand */
                break;
            }
            case OPR_EQ:
            case OPR_NE: {
                if (!tonumeral(v, null))
                    luaK_exp2RK(fs, v);
                /* else keep numeral, which may be an immediate operand */
                break;
            }
            case OPR_LT:
            case OPR_LE:
            case OPR_GT:
            case OPR_GE: {
                if (!isScNumber(v, new NumChecker()))
                    luaK_exp2anyreg(fs, v);
                /* else keep numeral, which may be an immediate operand */
                break;
            }
            default:break;
        }
    }



    /**
      Create code for '(e1 .. e2)'.
      For '(e1 .. e2.1 .. e2.2)' (which is '(e1 .. (e2.1 .. e2.2))',

     * 连接符号是 右连接的
     */
    static void codeconcat (FuncState fs, ExpDesc e1, ExpDesc e2, int line) {
        Instruction ie2 = previousInstruction(fs);
        //获取前一个指令，如果也是连接，进行优化
        if (getOpCode(ie2) == OP_CONCAT) {  /* is 'e2' a concatenation? */
            // argB表示了连接的范围
            int n = getArgB(ie2);  /* # of elements concatenated in 'e2' */
            freeExpReg(fs, e2);
            setArgA(ie2,e1.getInfo());  /* correct first element ('e1') */
            //多连接一个
            setArgB(ie2, n + 1);  /* will concatenate one more element */
        }
        else {  /* 'e2' is not a concatenation */
            //单个连接
            luaK_codeABC(fs, OP_CONCAT, e1.getInfo(), 2, 0);  /* new concat opcode */
            freeExpReg(fs, e2);
            luaK_fixline(fs, line);
        }
    }

    /**
     *  读取 完两个 操作数后，进行处理
     * @param fs
     * @param line
     */
    public static void luaK_posFix(FuncState fs,BinOpr opr,ExpDesc e1,ExpDesc e2,int line){
        luaK_dischargeVars(fs, e2);
        if (opr.foldBinaryOp() && constFolding(fs, opr.getOp() + LUA_OPADD, e1, e2))
            return;  /* done by folding */
        switch (opr) {
            case OPR_AND: {
                luaK_Concat(fs,e2,e1.getFJmp(),false);
                e1.setFromExp(e2);
                break;
            }
            case OPR_OR: {
                luaK_Concat(fs, e2, e1.getTJmp(),true);
                e1.setFromExp(e2);
                break;
            }
            case OPR_CONCAT: {  /* e1 .. e2 */
                luaK_exp2nextReg(fs, e2);
                codeconcat(fs, e1, e2, line);
                break;
            }
            case OPR_ADD: case OPR_MUL: {
                codeCommutative(fs, opr, e1, e2, line);
                break;
            }
            case OPR_SUB: {
                if (finishBinaryExpNegating(fs, e1, e2, OP_ADDI, line, TM_SUB))
                    break; /* coded as (r1 + -I) */
                /* ELSE */
            }  /* FALLTHROUGH */
            case OPR_DIV: case OPR_IDIV: case OPR_MOD: case OPR_POW: {
                codeArith(fs, opr, e1, e2, 0, line);
                break;
            }
            case OPR_BAND: case OPR_BOR: case OPR_BNOT: {
                codeBitWise(fs, opr, e1, e2, line);
                break;
            }
            case OPR_SHL: {
                if (isSCint(e1)) {
                    swapExps(e1, e2);
                    codeBinaryImediate(fs, OP_SHLI, e1, e2, 1, line, TM_SHL);  /* I << r2 */
                }
                else if (finishBinaryExpNegating(fs, e1, e2, OP_SHRI, line, TM_SHL)) {
                    /* coded as (r1 >> -I) */;
                }
                else  /* regular case (two registers) */
                    codeBinaryExpVal(fs, OP_SHL, e1, e2, line);
                break;
            }
            case OPR_SHR: {
                if (isSCint(e2))
                    codeBinaryImediate(fs, OP_SHRI, e1, e2, 0, line, TM_SHR);  /* r1 >> I */
                else  /* regular case (two registers) */
                    codeBinaryExpVal(fs, OP_SHR, e1, e2, line);
                break;
            }
            case OPR_EQ: case OPR_NE: {
                codeEq(fs, opr, e1, e2);
                break;
            }
            case OPR_LT: case OPR_LE: {
                OpCode op = OpCode.getOpCode(opr.getOp() - OPR_EQ.getOp());
                codeOrder(fs, op, e1, e2);
                break;
            }
            case OPR_GT: case OPR_GE: {
                /* '(a > b)' <=> '(b < a)';  '(a >= b)' <=> '(b <= a)' */
                OpCode op = OpCode.getOpCode(opr.getOp() - (OPR_NE.getOp()) + OP_EQ.getCode());
                swapExps(e1, e2);
                codeOrder(fs, op, e1, e2);
                break;
            }
            default: break;
        }
    }







    public static void luaK_fixline (FuncState fs, int line) {
        removeLastLineInfo(fs);
        saveLineInfo(fs, line);
    }


    /**
     * 浮点数，无小数部分
     */
    public static boolean floatNumNoPoint(double d){
        return (long)d == (long)Math.floor(d);
    }
    public static boolean isInteger(TValue v1){
        return v1.getValueType() == LUA_TNUMINT;
    }
    public static boolean isNumber(TValue v1){
       return v1.getValueType() == LUA_TNUMFLONT || v1.getValueType() == LUA_TNUMBER || v1.getValueType() == LUA_TNUMINT;
    }
    public static int intV(TValue v1){
        if(v1.getValueType() == LUA_TNUMFLONT){
            return (int)Math.floor(v1.getF());
        }
        return (int)v1.getI();
    }


    /**
     * 无效的指令
     */
    public static Instruction INVALID_INSTRUCTIN = new Instruction(~0);

    /**
     * 返回上个指令
     * 如果有 jump target 在当前指令 和 前一个指令之间，返回一个无效指令
     */
    public static Instruction previousInstruction(FuncState fs){
        int pc = fs.getPc();
        Proto proto = fs.getProto();
        if(pc > fs.getLasttarget()){
            return proto.getInstruction(pc-1);
        }
        return INVALID_INSTRUCTIN;
    }

    /**
     * 创建一个 OP_LOADNIL 指令
     */
    public static void luaK_Nil(FuncState fs,int from, int n){
        luaK_codeABC(fs,OP_LOADNIL,from,n-1,0);
    }

    /**
     * 获取 jump指令，跳转到的绝对地址
     *
     */
    public static int getJumpDestination(FuncState f,int pc){
        Instruction jmp = f.getProto().getInstruction(pc);
        int offset = getArgsJ(jmp);
        if(offset == NO_JUMP){
            return NO_JUMP;
        }
        return pc + 1 + offset;
    }
    /**
     * jmp 指令，跳转到指定位置; 在知道跳转地址时，跳转位置是NO_JUMP，现在要进行调整
     */
    public static void fixJump(FuncState fs,int pc,int dest){
        Instruction jmp = fs.getProto().getInstruction(pc);
        //获取相对偏移量
        int offset = dest - (pc + 1);
        setArgsJ(jmp,offset);
    }
    /**
     * 连接 跳转列表
     * type == true 连接 真出口跳转表
     * type == false 连接 假出口 跳转表
     *
     * expDesc 的 currTk，f分表是真假跳转表的头部
     */
    public static void luaK_Concat(FuncState fs,ExpDesc expDesc,int l2, boolean type){
        if(l2 == NO_JUMP){
            return;
        } else if(type && expDesc.getTJmp() == NO_JUMP){
            expDesc.setTJmp(l2);
        } else if(!type && expDesc.getFJmp() == NO_JUMP){
            expDesc.setFJmp(l2);
        }else{
            int list = type ? expDesc.getTJmp() : expDesc.getFJmp();
            int next;
            //找到最后一个 jump语句
            while((next = getJumpDestination(fs,list)) != NO_JUMP){
                list = next;
            }
            //将最后一个jump语句的跳转到l2
            fixJump(fs,list,l2);
        }
    }

    /**
     * 创建一个 jmp指令
     */
    public static int luaK_Jump(FuncState fs){
        return codesJ(fs,OP_JMP,NO_JUMP,0);
    }
    /**
     * 跳到指定的位置
     */
    public static void  luaK_JumpTo(FuncState fs,int t){
        luaK_patchList(fs,luaK_Jump(fs),t);
    }
    /**
     * 创建一个 return 指令
     * nret 代表返回值数量
     */
    public static void luaK_Ret(FuncState fs,int first,int nret){
        OpCode op;
        switch (nret){
            case 0 :op = OP_RETURN0;break;
            case 1 : op = OP_RETURN1;break;
            default:op = OP_RETURN;
        }
        luaK_codeABC(fs,op,first,nret + 1,0);
    }
    /**
     * 创建一个 条件跳转指令 （内部共创建了两条指令，一条条件跳转，一条 无条件跳转jmp）
     */
    public static int condJump(FuncState fs,OpCode op,int a,int b,int c,int k){
        luaK_codeABCk(fs,op,a,b,c,k);
        return luaK_Jump(fs);
    }

    /**
     * 返回当前pc，将其标记为一个jump target， 也就是跳转的label，这里并没有真正创建一个label，而是使用了标记的方式
     */
    public static int luaK_GetLabel(FuncState fs){
        fs.setLasttarget(fs.getPc());
        return fs.getPc();
    }
    /**
     * 如果是条件跳转，jmp指令前面有一个 条件跳转指令，这里是获取跳转的条件指令
     * 如果无条件跳转，返回自身
     */
    public static Instruction getJumpCongtrol(FuncState fs,int pc){
        Proto proto = fs.getProto();
        Instruction ins = proto.getInstruction(pc);
        if(pc >=1 && OpMode.isTestOp(getOpCode(proto.getInstruction(pc - 1)))){
            return proto.getInstruction(pc - 1);
        }
        return ins;
    }

    /**
     *设置 TESTSET值所存放的寄存器
     */
    public static boolean patchTestReg(FuncState fs,int node,int reg){
        //传入的node是一个jmp指令，根据jmp指令获取到 jumpcontrol指令
        Instruction i = getJumpCongtrol(fs,node);
        if(getOpCode(i) != OP_TESTSET){
            return false;
        }
        // R[A]=R[B]，所以不能是同一个寄存器
        if(reg != NO_REG && reg != getArgB(i)){
            setArgA(i,reg);
        } else{
            //无寄存器存放value 或者 A==B,值已经在寄存器里面了，将指令调整为TEST
            i.setIns(create_ABCK(OP_TEST.getCode(),getArgB(i),0,0,getArgk(i)).getIns());
        }
        return true;
    }

    /**
     * 遍历一系列 test 指令，确保没有指令会 产生值
     */
    public static void removeValues(FuncState fs,int list){
        for(; list != NO_JUMP;list = getJumpDestination(fs,list)){
            patchTestReg(fs,list,NO_REG);
        }
    }
    /**
     * 遍历一系列 test指令， 调整 跳转地址和寄存器；
     *  会产生value的 test 跳转到vtarget，值存放在寄存器 reg
     *  其他的跳转到dtarget
     */
    public static void patchListAux(FuncState fs,int list,int vtarget,int reg,int dtarget){
        while (list != NO_JUMP){
            int next =getJumpDestination(fs,list);
            //patchTestReg处理了 jumpcontro指令，需要再去处理jump指令，也就是 list
            if(patchTestReg(fs,list,reg)){
                fixJump(fs,list,vtarget);
            } else{
                //跳转到默认的target
                fixJump(fs,list,dtarget);
            }
            list = next;
        }
    }
    /**
     * 将 list 中的所有 jump ，都跳转到 target
     *
     * 函数会让所有产生值的TESTSET替换成TEST，
     */
    public static void luaK_patchList(FuncState fs,int list,int target){
        patchListAux(fs,list,target,NO_REG,target);
    }
    /**
     * 跳转到当前位置
     */
    public static void luaK_patchToHere(FuncState fs,int list){
        //获取一个跳转用的标签
        int here =luaK_GetLabel(fs);
        luaK_patchList(fs,list,here);
    }

    /**
     * 移除上一个指令的行号信息
     */
    public static void removeLastLineInfo(FuncState fs){
        Proto proto = fs.getProto();
        int pc = fs.getPc() - 1;
        fs.setPreviousline(fs.getPreviousline() - proto.getLineInfo(pc));
    }
    /**
     * 移除上一个指令
     */
    public static void removeLastInstruction(FuncState fs){
        removeLastLineInfo(fs);
        fs.removeLastInstruciton();
    }

    /**
     * 创建一个 sj指令
     */
    public static int codesJ(FuncState fs, OpCode o,int sj,int k){
        //!! createSj时用的sj是 加上了 OFFSET_sj的
        int j = sj + OFFSET_sJ;
        return luaK_code(fs,create_sJ(o.getCode(),j,k));
    }

    /**
     * 设置 Table的尺寸 这里进行了简化 不去处理
     * @param fs
     * @param pc
     * @param ra
     * @param asize
     * @param hsize
     */
    public static void luaK_setTableSize(FuncState fs,int pc, int ra,int asize,int hsize){
        Instruction inst = fs.getProto().getInstruction(pc);
        fs.getProto().setInstruction(pc,create_ABCK(OP_NEWTABLE.getCode(),ra,0,0,0));
        fs.getProto().setInstruction(pc+1,create_Ax(OP_EXTRAARG.getCode(),0));
    }

    /**
     * setList
     *   base是 存放 talbe 的寄存器
     *   nelems 是 #table 加上 现在要存放到table里面的内容
     *   tostore  存放到table元素的数量
     *
     * @param args
     */
    public static void luaK_setList(FuncState fs,int base,int nelems,int toStore){
        if(toStore == LUA_MULTRET){
            toStore = 0;
        }
        if(nelems <+ MAX_ARG_C){
            luaK_codeABC(fs,OP_SETLIST,base,toStore,nelems);
        } else{
            //长度很长时，需要两条指令表示
            int extra = nelems / (MAX_ARG_C + 1);
            nelems %= (MAX_ARG_C + 1);
            luaK_codeABCk(fs,OP_SETLIST,base,toStore,nelems,1);
            codeExtraArg(fs,extra);
        }
        //释放寄存器
        fs.setFreereg(base + 1);
    }

    /**
     * 返回 最终的跳转结果
     * @param args
     */
    public static int finalTarget(List<Instruction> codes,int i){
        int count;
        for(count = 0;count < 100; count++){
            Instruction pc = codes.get(i);
            if(getOpCode(pc) != OP_JMP){
                break;
            } else{
                i+= getArgsJ(pc) + 1;
            }
        }

        return i;
    }

    /**
     * 最后遍历一次函数的 代码 ，做一个 窥孔优化的调整
     * @param args
     */
    public static void luaK_Finish(FuncState fs){
        int i;
        Proto proto = fs.getProto();
        for(i=0;i<fs.getPc();i++){
            Instruction pc =proto.getInstruction(i);
            switch (getOpCode(pc)){
                case OP_RETURN0:case OP_RETURN1:{
                    //无需额外操作
                    if(!(fs.isNeedclose() || proto.isVararg())){
                        break;
                    }
                    setOpCode(pc,OP_RETURN);
                }

                case OP_RETURN: case OP_TAILCALL: {
                    if (fs.isNeedclose())
                        setArgk(pc, 1);  /* signal that it needs to close */
                    if (proto.isVararg())
                        setArgC(pc, proto.getNumparams() + 1);  /* signal that it is vararg */
                    break;
                }
                case OP_JMP: {
                    int target = finalTarget(proto.getCode(), i);
                    fixJump(fs, i, target);
                    break;
                }
                default:break;
            }
        }
    }


    public static void main(String[] args) {
          int j = -1 + OFFSET_sJ;
          Instruction ins =  create_sJ(OP_JMP.getCode(),j,0);
          System.out.println(Instructions.getArgsJ(ins));
          Instruction instruction = new Instruction();
          Instructions.setOpCode(instruction, OP_JMP);
          Instructions.setArgsJ(instruction,NO_JUMP);
          System.out.println(Instructions.getArgsJ(instruction));
    }




}

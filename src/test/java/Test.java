import com.jdy.lua.lcodes2.InstructionGenerator;
import com.jdy.lua.lex.Lex;
import com.jdy.lua.lex.LexState;
import com.jdy.lua.lex.Token;
import com.jdy.lua.lopcodes.Instruction;
import com.jdy.lua.lparser2.FunctionInfo;
import com.jdy.lua.lparser2.statement.BlockStatement;
import com.jdy.lua.lstate.LuaState;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;

import static com.jdy.lua.lparser2.LParser.block;

public class Test {
    public static void main(String[] args) throws Exception {
        func5();
    }


  public static void func5() throws FileNotFoundException{
        LexState lexState = new LexState();
        lexState.setCurrTk(new Token());
        lexState.setEnvn("_ENV");
        lexState.setL(new LuaState());
        lexState.setReader(new FileInputStream(new File("src/test/b.lua")));
        Lex.luaX_Next(lexState);
        BlockStatement b = block(lexState);
          FunctionInfo fi = new FunctionInfo();
          fi.addLocVar("_ENV",0);
          InstructionGenerator generator = new InstructionGenerator(fi);
          System.out.println();
          generator.generate(b);
          for(Instruction c : fi.getInstructions()){
              System.out.println(c);
          }
    }
}

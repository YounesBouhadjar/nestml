package org.nest.nestml.cocos;


import static de.se_rwth.commons.logging.Log.error;
import org.nest.nestml._ast.ASTInputLine;
import org.nest.nestml._cocos.NESTMLASTInputLineCoCo;

public class CurrentInputIsNotInhExc implements NESTMLASTInputLineCoCo {

  public static final String ERROR_CODE = "NESTML_CURRENT_INPUT_IS_NOT_INH_EXC";

  @Override
  public void check(ASTInputLine inputLine) {
    if (inputLine != null && inputLine.isCurrent()
            && inputLine.getInputTypes() != null) {
      if (!inputLine.getInputTypes().isEmpty()) {
        final String msg = "Current input can neither be inhibitory nor excitatory.";
       error(ERROR_CODE + ":" +  msg, inputLine.get_SourcePositionStart());
      }

    }

  }

}
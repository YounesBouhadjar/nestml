/*
 * Copyright (c) 2015 RWTH Aachen. All rights reserved.
 *
 * http://www.se-rwth.de/
 */
package org.nest.nestml._cocos;

import com.google.common.collect.Maps;
import static de.se_rwth.commons.logging.Log.error;

import de.monticore.ast.ASTNode;
import de.se_rwth.commons.SourcePosition;
import org.nest.nestml._ast.*;
import org.nest.spl._ast.ASTDeclaration;

import java.util.Map;

/**
 * This context condition checks, whether the state/parameter/internal-variables
 * of a component/neuron is not defined multiple times.
 *
 * E.g. in the following case x defined twice and results in an error
 * neuron NeuronInTest:
 *   state: x mV end
 *   parameter: x real end
 * end
 *
 * @author ippen, plotnikov
 */
public class MemberVariableDefinedMultipleTimes implements
    NESTMLASTNeuronCoCo,
    NESTMLASTComponentCoCo {

  public static final String ERROR_CODE = "NESTML_MEMBER_VARIABLE_DEFINED_MULTIPLE_TIMES";

  @Override
  public void check(ASTComponent comp) {
    check(comp.getBody());
  }

  @Override
  public void check(ASTNeuron neuron) {
    check(neuron.getBody());
  }

  private void check(ASTBody body) {
    Map<String, SourcePosition> varNames = Maps.newHashMap();
    body.getStateDeclarations()
        .forEach(aliasDecl -> addNames(varNames, aliasDecl.getDeclaration()));
    body.getParameterDeclarations()
        .forEach(aliasDecl -> addNames(varNames, aliasDecl.getDeclaration()));
    body.getInternalDeclarations()
        .forEach(aliasDecl -> addNames(varNames, aliasDecl.getDeclaration()));
    body.getInputLines()
        .forEach(inputLine -> addVariable(inputLine.getName(), varNames, inputLine) );
  }

  private void addNames(
      final Map<String, SourcePosition> names,
      final ASTDeclaration decl) {
    for (final String var : decl.getVars()) {
      addVariable(var, names, decl);
    }

  }

  private void addVariable(
      final String var,
      final Map<String, SourcePosition> names,
      final ASTNode astNode) {
    if (names.containsKey(var)) {
      CocoErrorStrings errorStrings = CocoErrorStrings.getInstance();
      final String msg = errorStrings.getErrorMsg(this,var,
              names.get(var).getLine(),
              names.get(var).getColumn());

     error(msg, astNode.get_SourcePositionStart());

    }
    else {
      names.put(var, astNode.get_SourcePositionStart());
    }
  }
}
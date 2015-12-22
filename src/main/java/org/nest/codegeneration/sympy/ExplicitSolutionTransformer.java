/*
 * Copyright (c) 2015 RWTH Aachen. All rights reserved.
 *
 * http://www.se-rwth.de/
 */
package org.nest.codegeneration.sympy;

import com.google.common.collect.Lists;
import de.monticore.ast.ASTNode;
import org.nest.nestml._ast.ASTAliasDecl;
import org.nest.nestml._ast.ASTBodyDecorator;
import org.nest.nestml._ast.ASTNESTMLCompilationUnit;
import org.nest.nestml._ast.ASTNESTMLNode;
import org.nest.nestml._visitor.NESTMLVisitor;
import org.nest.spl._ast.*;
import org.nest.symboltable.symbols.NESTMLNeuronSymbol;
import org.nest.symboltable.symbols.NESTMLVariableSymbol;
import org.nest.utils.ASTNodes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkState;

/**
 * Takes SymPy result and the source AST. Produces an altered AST with the integrated solution.
 *
 * @author plotnikov
 */
public class ExplicitSolutionTransformer {

  final SymPyOutput2NESTMLConverter converter2NESTML = new SymPyOutput2NESTMLConverter();

  public ASTNESTMLCompilationUnit replaceODEWithSymPySolution(
      ASTNESTMLCompilationUnit root,
      final Path pathToP00File,
      final Path PSCInitialValueFile,
      final Path stateVectorFile,
      final Path updateStepFile) {
    root = addP30(root, pathToP00File);
    root = addPSCInitialValue(root, PSCInitialValueFile);
    root = addStateVariablesAndUpdateStatements(root, stateVectorFile);
    root = replaceODE(root, updateStepFile);

    return root;
  }

  /**
   * Adds the declaration of the P00 value to the nestml model. Note: very NEST specific.
   */
  public ASTNESTMLCompilationUnit addP30(
      final ASTNESTMLCompilationUnit root,
      final Path pathToP00File) {

    final ASTAliasDecl p00Declaration = converter2NESTML.convertToAlias(pathToP00File);

    addToInternalBlock(root, p00Declaration);
    return root;
  }

  /**
   * Adds the declaration of the P00 value to the nestml model. Note: very NEST specific.
   */
  public ASTNESTMLCompilationUnit addPSCInitialValue(
      final ASTNESTMLCompilationUnit root,
      final Path pathPSCInitialValueFile) {

    final ASTAliasDecl pscInitialValue = converter2NESTML.convertToAlias(pathPSCInitialValueFile);

    addToInternalBlock(root, pscInitialValue);
    return root;
  }

  public ASTNESTMLCompilationUnit addStateVariablesAndUpdateStatements(
      final ASTNESTMLCompilationUnit root,
      final Path stateVectorFile) {
    try {
      final List<String> stateVectorLines = Files
          .lines(stateVectorFile)
          .collect(Collectors.toList());

      Collections.reverse(stateVectorLines);

      checkState(stateVectorLines.size() > 0, "False stateVector.mat format. Check SymPy solver.");

      // First entry is the number of variables
      final Integer stateVariablesNumber = stateVectorLines.size();

      final List<String> stateVariableDeclarations = Lists.newArrayList();
      stateVariableDeclarations.add("y0 real");
      for (int i = 1; i <= stateVariablesNumber; ++i) {
        stateVariableDeclarations.add("y"+ i + " real");
      }

      stateVariableDeclarations.stream()
          .map(converter2NESTML::convertStringToAlias)
          .forEach(astAliasDecl -> addToStateBlock(root, astAliasDecl));

      // remaining entries are y_index update entries
      // these statements must be printed at the end of the dynamics function
      ASTBodyDecorator astBodyDecorator = new ASTBodyDecorator(root.getNeurons().get(0).getBody());

      for (final String line:stateVectorLines) {
        final ASTAssignment yVarAssignment = converter2NESTML.convertStringToAssignment(line);

        // Depends on the SPL grammar structure
        addAssignmentToDynamics(astBodyDecorator, yVarAssignment);
      }
      // add extra handling of the y0 variable
      // print resulted model to the file
      final NESTMLNeuronSymbol nestmlNeuronSymbol = (NESTMLNeuronSymbol)
          root.getNeurons().get(0).getSymbol().get();

      if (!nestmlNeuronSymbol.getCurrentBuffers().isEmpty()) {
        final NESTMLVariableSymbol currentBuffer = nestmlNeuronSymbol.getCurrentBuffers().get(0);
        final ASTAssignment y0Assignment = converter2NESTML
            .convertStringToAssignment("y0 = " + currentBuffer.getName() + ".getSum(t)");
        addAssignmentToDynamics(astBodyDecorator, y0Assignment);


      }

      if (!nestmlNeuronSymbol.getCurrentBuffers().isEmpty()) {
        final NESTMLVariableSymbol spikeBuffer = nestmlNeuronSymbol.getSpikeBuffers().get(0);
        final ASTAssignment pscUpdateStep = converter2NESTML
            .convertStringToAssignment("y1 = " + spikeBuffer.getName() + ".getSum(t)");
        addAssignmentToDynamics(astBodyDecorator, pscUpdateStep);
      }
      return root;
    }
    catch (IOException e) {
      throw new RuntimeException("Cannot process stateVector output from the SymPy solver", e);
    }
  }

  public ASTNESTMLCompilationUnit replaceODE(
      final ASTNESTMLCompilationUnit root,
      final Path updateStepFile) {
    final ASTAssignment stateUpdate = converter2NESTML.convertToAssignment(updateStepFile);

    ASTBodyDecorator astBodyDecorator = new ASTBodyDecorator(root.getNeurons().get(0).getBody());
    final ODECollector odeCollector = new ODECollector();
    odeCollector.startVisitor(astBodyDecorator.getDynamics().get(0));
    checkState(odeCollector.getFoundOde().isPresent());

    final Optional<ASTNode> parent = ASTNodes.getParent(odeCollector.getFoundOde().get(), root);
    checkState(parent.isPresent());
    checkState(parent.get() instanceof ASTSmall_Stmt);

    final ASTSmall_Stmt castedParent = (ASTSmall_Stmt) parent.get();
    castedParent.setOdeDeclaration(null);
    castedParent.setAssignment(stateUpdate);

    return root;
  }

  private class ODECollector implements NESTMLVisitor {
    private Optional<ASTOdeDeclaration> foundOde = Optional.empty();

    public void startVisitor(ASTNESTMLNode node) {
      node.accept(this);
    }

    @Override
    public void visit(final ASTOdeDeclaration astOdeDeclaration) {
      foundOde = Optional.of(astOdeDeclaration);
    }

    public Optional<ASTOdeDeclaration> getFoundOde() {
      return foundOde;
    }

  }

  private void addAssignmentToDynamics(ASTBodyDecorator astBodyDecorator,
      ASTAssignment yVarAssignment) {
    final ASTStmt astStmt = SPLNodeFactory.createASTStmt();
    final ASTSimple_Stmt astSimpleStmt = SPLNodeFactory.createASTSimple_Stmt();
    final List<ASTSmall_Stmt> astSmallStmts = Lists.newArrayList();
    final ASTSmall_Stmt astSmall_stmt = SPLNodeFactory.createASTSmall_Stmt();

    astStmt.setSimple_Stmt(astSimpleStmt);
    astSmallStmts.add(astSmall_stmt);
    astSimpleStmt.setSmall_Stmts(astSmallStmts);

    // Goal: add the y-assignments at the end of the expression
    astSmall_stmt.setAssignment(yVarAssignment);

    astBodyDecorator.getDynamics().get(0).getBlock().getStmts().add(astStmt);
  }

  // TODO do I need functions? Try to express it as lambda
  private void addToInternalBlock(
      final ASTNESTMLCompilationUnit root,
      final ASTAliasDecl declaration) {
    final ASTBodyDecorator astBodyDecorator = new ASTBodyDecorator(root.getNeurons().get(0).getBody());
    astBodyDecorator.addToInternalBlock(declaration);
  }

  private void addToStateBlock(
      final ASTNESTMLCompilationUnit root,
      final ASTAliasDecl declaration) {
    final ASTBodyDecorator astBodyDecorator = new ASTBodyDecorator(root.getNeurons().get(0).getBody());
    astBodyDecorator.addToStateBlock(declaration);
  }

}
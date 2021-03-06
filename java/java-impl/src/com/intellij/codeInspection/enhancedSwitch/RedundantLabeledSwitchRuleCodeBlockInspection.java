// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.codeInspection.enhancedSwitch;

import com.intellij.codeInspection.*;
import com.intellij.openapi.project.Project;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.util.ObjectUtils;
import com.siyeh.ig.psiutils.CommentTracker;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static com.intellij.codeInspection.InspectionsBundle.message;

/**
 * @author Pavel.Dolgov
 */
public class RedundantLabeledSwitchRuleCodeBlockInspection extends LocalInspectionTool {
  @NotNull
  @Override
  public PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
    if (!PsiUtil.getLanguageLevel(holder.getFile()).isAtLeast(LanguageLevel.JDK_12_PREVIEW)) {
      return PsiElementVisitor.EMPTY_VISITOR;
    }
    return new JavaElementVisitor() {
      @Override
      public void visitSwitchLabeledRuleStatement(PsiSwitchLabeledRuleStatement statement) {
        super.visitSwitchLabeledRuleStatement(statement);

        PsiStatement body = statement.getBody();
        if (body instanceof PsiBlockStatement) {
          PsiCodeBlock codeBlock = ((PsiBlockStatement)body).getCodeBlock();
          PsiStatement bodyStatement = unwrapSingleStatementCodeBlock(codeBlock);

          if (bodyStatement instanceof PsiBreakStatement) {
            PsiBreakStatement breakStatement = (PsiBreakStatement)bodyStatement;
            if (breakStatement.getValueExpression() != null) {
              PsiKeyword breakKeyword = ObjectUtils.tryCast(breakStatement.getFirstChild(), PsiKeyword.class);
              registerProblem(breakKeyword);
            }
          }
          else if (bodyStatement instanceof PsiThrowStatement || bodyStatement instanceof PsiExpressionStatement) {
            registerProblem(codeBlock.getLBrace());
            if (isOnTheFly) registerProblem(codeBlock.getRBrace());
          }
        }
      }

      private void registerProblem(@Nullable PsiElement element) {
        if (element != null) {
          holder.registerProblem(element,
                                 message("inspection.labeled.switch.rule.redundant.code.block.message"),
                                 ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                                 new UnwrapCodeBlockFix());
        }
      }
    };
  }

  @Nullable
  private static PsiStatement unwrapSingleStatementCodeBlock(PsiCodeBlock block) {
    PsiStatement firstStatement = PsiTreeUtil.getNextSiblingOfType(block.getLBrace(), PsiStatement.class);
    if (firstStatement != null && PsiTreeUtil.getNextSiblingOfType(firstStatement, PsiStatement.class) == null) {
      return firstStatement;
    }
    return null;
  }

  private static class UnwrapCodeBlockFix implements LocalQuickFix {
    @Nls(capitalization = Nls.Capitalization.Sentence)
    @NotNull
    @Override
    public String getFamilyName() {
      return message("inspection.labeled.switch.rule.redundant.code.fix.name");
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
      PsiBlockStatement body = PsiTreeUtil.getParentOfType(descriptor.getStartElement(), PsiBlockStatement.class);
      if (body != null && body.getParent() instanceof PsiSwitchLabeledRuleStatement) {
        PsiStatement bodyStatement = unwrapSingleStatementCodeBlock(body.getCodeBlock());
        if (bodyStatement instanceof PsiBreakStatement) {
          unwrapBreakValue(body, (PsiBreakStatement)bodyStatement);
        }
        else if (bodyStatement instanceof PsiThrowStatement || bodyStatement instanceof PsiExpressionStatement) {
          unwrap(body, bodyStatement);
        }
      }
    }

    private static void unwrapBreakValue(PsiStatement body, PsiBreakStatement breakStatement) {
      PsiExpression valueExpression = breakStatement.getValueExpression();
      if (valueExpression != null) {
        CommentTracker tracker = new CommentTracker();
        tracker.replaceAndRestoreComments(body, tracker.text(valueExpression) + ';');
      }
    }

    private static void unwrap(PsiStatement body, PsiStatement bodyStatement) {
      CommentTracker tracker = new CommentTracker();
      tracker.replaceAndRestoreComments(body, bodyStatement);
    }
  }
}

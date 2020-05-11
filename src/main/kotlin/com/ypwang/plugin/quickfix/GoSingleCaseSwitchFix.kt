//package com.ypwang.plugin.quickfix
//
//import com.goide.psi.GoExprSwitchStatement
//import com.goide.psi.impl.GoElementFactory
//import com.intellij.codeInspection.LocalQuickFixOnPsiElement
//import com.intellij.openapi.project.Project
//import com.intellij.psi.PsiElement
//import com.intellij.psi.PsiFile
//
//class GoSingleCaseSwitchFix(element: GoExprSwitchStatement): LocalQuickFixOnPsiElement(element) {
//    override fun getFamilyName(): String = text
//
//    override fun getText(): String = "Rewrite to 'if' statement"
//
//    override fun invoke(project: Project, file: PsiFile, startElement: PsiElement, endElement: PsiElement) {
//        val switchStatement = startElement as GoExprSwitchStatement
//        val singleCase = switchStatement.exprCaseClauseList.single()
//
////        switchStatement.condition!!.text
////        GoElementFactory.createIfStatement(project,
////                "${switchStatement.condition!!.text} == ${singleCase}",
////                singleCase.
////        )
//    }
//}
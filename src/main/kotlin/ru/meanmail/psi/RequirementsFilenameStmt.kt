package ru.meanmail.psi

import com.intellij.psi.PsiElement

interface RequirementsFilenameStmt : RequirementsNamedElement {
    
    val filename: String?
    
    override fun getName(): String?
    
    override fun setName(newName: String): PsiElement
    
    override fun getNameIdentifier(): PsiElement?
    
}

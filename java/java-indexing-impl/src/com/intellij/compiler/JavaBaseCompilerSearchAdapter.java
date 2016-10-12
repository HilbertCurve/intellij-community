/*
 * Copyright 2000-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.compiler;

import com.intellij.lang.ASTNode;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.impl.LibraryScopeCache;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.impl.java.stubs.PsiClassStub;
import com.intellij.psi.impl.java.stubs.impl.PsiClassStubImpl;
import com.intellij.psi.impl.source.PsiFileImpl;
import com.intellij.psi.impl.source.PsiFileWithStubSupport;
import com.intellij.psi.search.searches.ClassInheritorsSearch;
import com.intellij.psi.stubs.StubElement;
import com.intellij.psi.stubs.StubTree;
import com.intellij.psi.util.ClassUtil;
import com.intellij.util.ObjectUtils;
import com.intellij.util.Processor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

public class JavaBaseCompilerSearchAdapter implements ClassResolvingCompilerSearchAdapter<PsiClass> {
  public static final JavaBaseCompilerSearchAdapter INSTANCE = new JavaBaseCompilerSearchAdapter();

  @Override
  public boolean needOverrideElement() {
    return true;
  }

  @Nullable
  @Override
  public CompilerElement asCompilerElement(@NotNull PsiElement element) {
    if (mayBeVisibleOutsideOwnerFile(element)) {
      if (element instanceof PsiField) {
        final PsiField field = (PsiField)element;
        final PsiClass aClass = field.getContainingClass();
        if (aClass == null || aClass instanceof PsiAnonymousClass) return null;
        final String jvmOwnerName = ClassUtil.getJVMClassName(aClass);
        final String name = field.getName();
        if (name == null || jvmOwnerName == null) return null;
        return new CompilerElement.CompilerField(jvmOwnerName, name);
      }
      else if (element instanceof PsiMethod) {
        final PsiClass aClass = ((PsiMethod)element).getContainingClass();
        if (aClass == null || aClass instanceof PsiAnonymousClass) return null;
        final String jvmOwnerName = ClassUtil.getJVMClassName(aClass);
        if (jvmOwnerName == null) return null;
        final PsiMethod method = (PsiMethod)element;
        final String name = method.isConstructor() ? "<init>" : method.getName();
        final int parametersCount = method.getParameterList().getParametersCount();
        return new CompilerElement.CompilerMethod(jvmOwnerName, name, parametersCount);
      }
      else if (element instanceof PsiClass) {
        final String jvmClassName = ClassUtil.getJVMClassName((PsiClass)element);
        if (jvmClassName != null) {
          return new CompilerElement.CompilerClass(jvmClassName);
        }
      }
    }
    return null;
  }

  @NotNull
  @Override
  public CompilerElement[] getHierarchyRestrictedToLibrariesScope(@NotNull CompilerElement baseLibraryElement, @NotNull PsiElement baseLibraryPsi) {
    final PsiClass baseClass = ObjectUtils.notNull(baseLibraryPsi instanceof PsiClass ? (PsiClass)baseLibraryPsi : ((PsiMember)baseLibraryPsi).getContainingClass());
    final List<CompilerElement> overridden = new ArrayList<>();
    Processor<PsiClass> processor = c -> {
      if (c.hasModifierProperty(PsiModifier.PRIVATE)) return true;
      String qName = c.getQualifiedName();
      if (qName == null) return true;
      overridden.add(baseLibraryElement.override(qName));
      return true;
    };
    ClassInheritorsSearch.search(baseClass, LibraryScopeCache.getInstance(baseClass.getProject()).getLibrariesOnlyScope(), true).forEach(processor);
    return overridden.toArray(new CompilerElement[overridden.size()]);
  }

  @NotNull
  @Override
  public PsiClass[] getCandidatesFromFile(@NotNull Collection<String> classInternalNames,
                                          @NotNull PsiNamedElement superClass,
                                          @NotNull VirtualFile containingFile,
                                          @NotNull Project project) {
    Collection<InternalClassMatcher> matchers = createClassMatcher(classInternalNames, superClass);
    return retrieveMatchedClasses(containingFile, project, matchers).toArray(PsiClass.EMPTY_ARRAY);
  }

  private static boolean mayBeVisibleOutsideOwnerFile(@NotNull PsiElement element) {
    if (!(element instanceof PsiModifierListOwner)) return true;
    if (((PsiModifierListOwner)element).hasModifierProperty(PsiModifier.PRIVATE)) return false;
    return true;
  }

  private static List<PsiClass> retrieveMatchedClasses(VirtualFile file, Project project, Collection<InternalClassMatcher> matchers) {
    final List<PsiClass> result = new ArrayList<>(matchers.size());
    PsiFileWithStubSupport psiFile = ObjectUtils.notNull((PsiFileWithStubSupport)PsiManager.getInstance(project).findFile(file));
    StubTree tree = psiFile.getStubTree();
    boolean foreign = tree == null;
    if (foreign) {
      tree = ((PsiFileImpl)psiFile).calcStubTree();
    }

    for (StubElement<?> element : tree.getPlainList()) {
      if (element instanceof PsiClassStub && match((PsiClassStub)element, matchers)) {
        result.add(asPsi((PsiClassStub<?>)element, psiFile, tree, foreign));
      }
    }

    return result;
  }

  private static PsiClass asPsi(PsiClassStub<?> stub, PsiFileWithStubSupport file, StubTree tree, boolean foreign) {
    if (foreign) {
      final PsiClass cachedPsi = ((PsiClassStubImpl<?>)stub).getCachedPsi();
      if (cachedPsi != null) return cachedPsi;

      final ASTNode ast = file.findTreeForStub(tree, stub);
      return ast != null ? (PsiClass)ast.getPsi() : null;
    }
    return stub.getPsi();
  }

  private static boolean match(PsiClassStub stub, Collection<InternalClassMatcher> matchers) {
    for (InternalClassMatcher matcher : matchers) {
      if (matcher.matches(stub)) {
        //qualified name is unique among file's classes
        if (matcher instanceof InternalClassMatcher.ByQualifiedName) {
          matchers.remove(matcher);
        }
        return true;
      }
    }
    return false;
  }

  private static Collection<InternalClassMatcher> createClassMatcher(@NotNull Collection<String> internalNames, @NotNull PsiNamedElement baseClass) {
    boolean matcherBySuperNameAdded = false;
    final List<InternalClassMatcher> matchers = new ArrayList<>(internalNames.size());
    for (String internalName : internalNames) {
      int curLast = internalName.length() - 1;
      while (true) {
        int lastIndex = internalName.lastIndexOf('$', curLast);
        if (lastIndex > -1 && lastIndex < internalName.length() - 1) {
          final int followingIndex = lastIndex + 1;
          final boolean digit = Character.isDigit(internalName.charAt(followingIndex));
          if (digit) {
            if (curLast == internalName.length() - 1) {
              final int nextNonDigit = getNextNonDigitIndex(internalName, followingIndex);
              if (nextNonDigit == -1) {
                if (matcherBySuperNameAdded) {
                  break;
                }
                matcherBySuperNameAdded = true;
                //anonymous
                matchers.add(new InternalClassMatcher.BySuperName(baseClass.getName()));
                break;
              } else {
                //declared inside method
                matchers.add(new InternalClassMatcher.ByName(internalName.substring(nextNonDigit)));
              }
            }
            else {
              //declared in anonymous
              matchers.add(new InternalClassMatcher.ByName(StringUtil.getShortName(internalName, '$')));
              break;
            }
          }
        }
        else {
          matchers.add(new InternalClassMatcher.ByQualifiedName(StringUtil.replace(internalName, "$", ".")));
          break;
        }
        curLast = lastIndex - 1;
      }
    }
    return matchers;
  }

  private static int getNextNonDigitIndex(String name, int digitIndex) {
    for (int i = digitIndex + 1; i < name.length(); i++) {
      if (!Character.isDigit(name.charAt(i))) {
        return i;
      }
    }
    return -1;
  }

  private interface InternalClassMatcher {
    boolean matches(PsiClassStub stub);

    class BySuperName implements InternalClassMatcher {
      private final String mySuperName;

      public BySuperName(String name) {mySuperName = name;}

      @Override
      public boolean matches(PsiClassStub stub) {
        return stub.isAnonymous() && mySuperName.equals(PsiNameHelper.getShortClassName(stub.getBaseClassReferenceText()));
      }
    }

    class ByName implements InternalClassMatcher {
      private final String myName;

      public ByName(String name) {myName = name;}

      @Override
      public boolean matches(PsiClassStub stub) {
        return myName.equals(stub.getName());
      }
    }

    class ByQualifiedName implements InternalClassMatcher {
      private final String myQName;

      public ByQualifiedName(String name) {myQName = name;}

      @Override
      public boolean matches(PsiClassStub stub) {
        return myQName.equals(stub.getQualifiedName());
      }
    }
  }
}

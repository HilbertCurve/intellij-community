// Copyright 2000-2021 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.plugins.groovy.dsl;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.impl.FakePsiElement;
import com.intellij.psi.scope.PsiScopeProcessor;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.FList;
import groovy.lang.Closure;
import groovy.lang.GroovyObjectSupport;
import groovy.lang.MetaMethod;
import org.codehaus.groovy.runtime.DefaultGroovyMethods;
import org.codehaus.groovy.runtime.InvokerHelper;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.groovy.dsl.dsltop.GdslMembersProvider;
import org.jetbrains.plugins.groovy.dsl.holders.CompoundMembersHolder;
import org.jetbrains.plugins.groovy.dsl.holders.CustomMembersHolder;
import org.jetbrains.plugins.groovy.dsl.holders.DeclarationType;
import org.jetbrains.plugins.groovy.dsl.holders.NonCodeMembersHolder;
import org.jetbrains.plugins.groovy.dsl.toplevel.ClassContextFilter;
import org.jetbrains.plugins.groovy.extensions.NamedArgumentDescriptor;
import org.jetbrains.plugins.groovy.extensions.impl.NamedArgumentDescriptorImpl;
import org.jetbrains.plugins.groovy.lang.completion.closureParameters.ClosureDescriptor;
import org.jetbrains.plugins.groovy.lang.psi.GroovyPsiElement;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrMethodCall;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrReferenceExpression;
import org.jetbrains.plugins.groovy.lang.psi.util.GroovyCommonClassNames;
import org.jetbrains.plugins.groovy.lang.psi.util.GroovyPropertyUtils;
import org.jetbrains.plugins.groovy.lang.psi.util.PsiUtil;

import java.util.*;
import java.util.function.Consumer;

/**
 * @author peter
 */
public class CustomMembersGenerator extends GroovyObjectSupport implements GdslMembersHolderConsumer {
  private static final Logger LOG = Logger.getInstance(CustomMembersGenerator.class);
  private static final GdslMembersProvider[] PROVIDERS = GdslMembersProvider.EP_NAME.getExtensions();
  public static final @NonNls String THROWS = "throws";
  private FList<Map> myDeclarations = FList.emptyList();
  private final CompoundMembersHolder myDepot = new CompoundMembersHolder();
  private final GroovyClassDescriptor myDescriptor;
  @Nullable private final Map<String, List> myBindings;

  public CustomMembersGenerator(@NotNull GroovyClassDescriptor descriptor, @Nullable Map<String, List> bindings) {
    myDescriptor = descriptor;
    myBindings = bindings;
  }

  @Override
  public PsiElement getPlace() {
    return myDescriptor.getPlace();
  }

  @Override
  @Nullable
  public PsiClass getClassType() {
    return getPsiClass();
  }

  @Override
  public PsiType getPsiType() {
    return myDescriptor.getPsiType();
  }

  @Nullable
  @Override
  public PsiClass getPsiClass() {
    return myDescriptor.getPsiClass();
  }

  @Override
  public GlobalSearchScope getResolveScope() {
    return myDescriptor.getResolveScope();
  }

  @Override
  public Project getProject() {
    return myDescriptor.getProject();
  }

  @Nullable
  public CustomMembersHolder getMembersHolder() {
    if (!myDeclarations.isEmpty()) {
      addMemberHolder(new CustomMembersHolder() {
        @Override
        public boolean processMembers(GroovyClassDescriptor descriptor, PsiScopeProcessor processor, ResolveState state) {
          return NonCodeMembersHolder.generateMembers(ContainerUtil.reverse(myDeclarations), descriptor.justGetPlaceFile()).processMembers(
            descriptor, processor, state);
        }

        @Override
        public void consumeClosureDescriptors(GroovyClassDescriptor descriptor, Consumer<? super ClosureDescriptor> consumer) {
          NonCodeMembersHolder.generateMembers(ContainerUtil.reverse(myDeclarations), descriptor.justGetPlaceFile())
            .consumeClosureDescriptors(descriptor, consumer);
        }
      });
    }
    return myDepot;
  }

  @Override
  public void addMemberHolder(CustomMembersHolder holder) {
    myDepot.addHolder(holder);
  }

  private Object[] constructNewArgs(Object[] args) {
    final Object[] newArgs = new Object[args.length + 1];
    //noinspection ManualArrayCopy
    for (int i = 0; i < args.length; i++) {
      newArgs[i] = args[i];
    }
    newArgs[args.length] = this;
    return newArgs;
  }


  /** **********************************************************
   Methods to add new behavior
   *********************************************************** */
  public void property(Map<Object, Object> args) {
    if (args == null) return;

    String name = (String)args.get("name");
    Object type = args.get("type");
    Object doc = args.get("doc");
    Object docUrl = args.get("docUrl");
    Boolean isStatic = (Boolean)args.get("isStatic");

    Map<@NonNls Object, Object> getter = new HashMap<>();
    getter.put("name", GroovyPropertyUtils.getGetterNameNonBoolean(name));
    getter.put("type", type);
    getter.put("isStatic", isStatic);
    getter.put("doc", doc);
    getter.put("docUrl", docUrl);
    method(getter);

    Map<@NonNls Object, @NonNls Object> setter = new HashMap<>();
    setter.put("name", GroovyPropertyUtils.getSetterName(name));
    setter.put("type", "void");
    setter.put("isStatic", isStatic);
    setter.put("doc", doc);
    setter.put("docUrl", docUrl);
    final HashMap<Object, Object> param = new HashMap<>();
    param.put(name, type);
    setter.put("params", param);
    method(setter);
  }

  public void constructor(Map<Object, Object> args) {
    if (args == null) return;

    args.put("constructor", true);
    method(args);
  }

  public ParameterDescriptor parameter(Map args) {
    return new ParameterDescriptor(args, myDescriptor.justGetPlaceFile());
  }

  public void method(Map<Object, Object> args) {
    if (args == null) return;

    args = new LinkedHashMap<>(args);
    parseMethod(args);
    args.put("declarationType", DeclarationType.METHOD);
    myDeclarations = myDeclarations.prepend(args);
  }

  public void methodCall(Closure<Map<Object, Object>> generator) {
    PsiElement place = myDescriptor.getPlace();

    PsiElement parent = place.getParent();
    if (isMethodCall(place, parent)) {
      assert parent instanceof GrMethodCall && place instanceof GrReferenceExpression;

      GrReferenceExpression ref = (GrReferenceExpression)place;

      PsiType[] argTypes = PsiUtil.getArgumentTypes(ref, false);
      if (argTypes == null) return;

      String[] types =
      ContainerUtil.map(argTypes, PsiType::getCanonicalText, new String[argTypes.length]);

      generator.setDelegate(this);

      HashMap<String, Object> args = new HashMap<>();
      args.put("name", ref.getReferenceName());
      args.put("argumentTypes", types);
      generator.call(args);
    }
  }

  private static boolean isMethodCall(PsiElement place, PsiElement parent) {
    return place instanceof GrReferenceExpression &&
        parent instanceof GrMethodCall &&
        ((GrMethodCall)parent).getInvokedExpression() == place;
  }

  @SuppressWarnings("unchecked")
  private static void parseMethod(@NonNls Map args) {
    String type = stringifyType(args.get("type"));
    args.put("type", type);

    Object namedParams = args.get("namedParams");
    if (namedParams instanceof List) {
      @NonNls LinkedHashMap newParams = new LinkedHashMap();
      newParams.put("args", namedParams);
      Object oldParams = args.get("params");
      if (oldParams instanceof Map) {
        newParams.putAll((Map)oldParams);
      }
      args.put("params", newParams);
    }

    Object params = args.get("params");
    if (params instanceof Map) {
      boolean first = true;
      for (Map.Entry<Object, Object> entry : ((Map<Object, Object>)params).entrySet()) {
        Object value = entry.getValue();
        if (!first || !(value instanceof List)) {
          entry.setValue(stringifyType(value));
        }
        first = false;
      }
    }
    final Object toThrow = args.get(THROWS);
    if (toThrow instanceof List) {
      final ArrayList<String> list = new ArrayList<>();
      for (Object o : (List)toThrow) {
        list.add(stringifyType(o));
      }
      args.put(THROWS, list);
    }
    else if (toThrow != null) {
      args.put(THROWS, Collections.singletonList(stringifyType(toThrow)));
    }

  }

  @SuppressWarnings("UnusedDeclaration")
  public void closureInMethod(Map<Object, Object> args) {
    if (args == null) return;

    args = new LinkedHashMap<>(args);
    parseMethod(args);
    final Object method = args.get("method");
    if (method instanceof Map) {
      parseMethod((Map)method);
    }
    args.put("declarationType", DeclarationType.CLOSURE);
    myDeclarations = myDeclarations.prepend(args);
  }

  public void variable(Map<Object, Object> args) {
    if (args == null) return;

    args = new LinkedHashMap<>(args);
    parseVariable(args);
    myDeclarations = myDeclarations.prepend(args);
  }

  private static void parseVariable(Map<Object, Object> args) {
    String type = stringifyType(args.get("type"));
    args.put("type", type);
    args.put("declarationType", DeclarationType.VARIABLE);
  }

  private static String stringifyType(Object type) {
    if (type == null) return CommonClassNames.JAVA_LANG_OBJECT;
    if (type instanceof Closure) return GroovyCommonClassNames.GROOVY_LANG_CLOSURE;
    if (type instanceof Map) return CommonClassNames.JAVA_UTIL_MAP;
    if (type instanceof Class) return ((Class)type).getName();

    String s = type.toString();
    LOG.assertTrue(!s.startsWith("? extends"), s); // NON-NLS
    LOG.assertTrue(!s.contains("?extends"), s); // NON-NLS
    LOG.assertTrue(!s.contains("<null."), s); // NON-NLS
    LOG.assertTrue(!s.startsWith("null."), s); // NON-NLS
    LOG.assertTrue(!(s.contains(",") && !s.contains("<")), s);
    return s;
  }

  @SuppressWarnings("UnusedDeclaration")
  @Nullable
  public Object methodMissing(String name, Object args) {
    final Object[] newArgs = constructNewArgs((Object[])args);

    // Get other DSL methods from extensions
    for (GdslMembersProvider provider : PROVIDERS) {
      final List<MetaMethod> variants = DefaultGroovyMethods.getMetaClass(provider).respondsTo(provider, name, newArgs);
      if (variants.size() == 1) {
        return InvokerHelper.invokeMethod(provider, name, newArgs);
      }
    }
    return null;
  }

  @SuppressWarnings("UnusedDeclaration")
  @Nullable
  public Object propertyMissing(String name) {
    if (myBindings != null) {
      final List list = myBindings.get(name);
      if (list != null) {
        return list;
      }
    }

    return null;
  }

  public static final class ParameterDescriptor {
    public final String name;
    public final NamedArgumentDescriptor descriptor;

    private ParameterDescriptor(Map args, PsiElement context) {
      name = (String)args.get("name");
      final String typeText = stringifyType(args.get("type"));
      Object doc = args.get("doc");
      GdslNamedParameter parameter = new GdslNamedParameter(name, doc instanceof String ? (String)doc : null, context, typeText);
      descriptor = new NamedArgumentDescriptorImpl(NamedArgumentDescriptor.Priority.ALWAYS_ON_TOP, parameter) {
        @Override
        public boolean checkType(@NotNull PsiType type, @NotNull GroovyPsiElement context) {
          return typeText == null || ClassContextFilter.isSubtype(type, context.getContainingFile(), typeText);
        }
      };
    }

  }

  public static class GdslNamedParameter extends FakePsiElement {
    private final String myName;
    public final String docString;
    private final PsiElement myParent;
    @Nullable public final String myParameterTypeText;

    public GdslNamedParameter(String name, String doc, @NotNull PsiElement parent, @Nullable String type) {
      myName = name;
      this.docString = doc;
      myParent = parent;
      myParameterTypeText = type;
    }

    @Override
    public PsiElement getParent() {
      return myParent;
    }

    @Override
    public String getName() {
      return myName;
    }
  }

}

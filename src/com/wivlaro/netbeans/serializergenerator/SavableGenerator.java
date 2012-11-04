package com.wivlaro.netbeans.serializergenerator;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import com.wivlaro.jme3.export.SerializeFieldConfig;
import com.wivlaro.jme3.export.SerializerMethodConfig;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;
import javax.swing.text.StyledDocument;
import org.netbeans.api.editor.mimelookup.MimeRegistration;
import org.netbeans.api.java.source.CancellableTask;
import org.netbeans.api.java.source.Comment;
import org.netbeans.api.java.source.ElementHandle;
import org.netbeans.api.java.source.JavaSource;
import org.netbeans.api.java.source.ModificationResult;
import org.netbeans.api.java.source.SourceUtils;
import org.netbeans.api.java.source.TreeMaker;
import org.netbeans.api.java.source.TypeUtilities;
import org.netbeans.api.java.source.WorkingCopy;
import org.netbeans.spi.editor.codegen.CodeGenerator;
import org.netbeans.spi.editor.codegen.CodeGeneratorContextProvider;
import org.openide.cookies.EditorCookie;
import org.openide.filesystems.FileObject;
import org.openide.loaders.DataObject;
import org.openide.util.Lookup;

public class SavableGenerator implements CodeGenerator {
	
	private static final int LOG = 0;

	JTextComponent textComp;

	/**
	 *
	 * @param context containing JTextComponent and possibly other items registered by {@link CodeGeneratorContextProvider}
	 */
	private SavableGenerator(Lookup context) { // Good practice is not to save Lookup outside ctor
		textComp = context.lookup(JTextComponent.class);
	}

	@MimeRegistration(mimeType = "text/x-java", service = CodeGenerator.Factory.class)
	public static class Factory implements CodeGenerator.Factory {

		@Override
		public List<? extends CodeGenerator> create(Lookup context) {
			return Collections.singletonList(new SavableGenerator(context));
		}
	}

	/**
	 * The name which will be inserted inside Insert Code dialog
	 */
	@Override
	public String getDisplayName() {
		return "Generate Savable read/write";
	}

	/**
	 * This will be invoked when user chooses this Generator from Insert Code dialog
	 */
	@Override
	public void invoke() {
		try {
			Document doc = textComp.getDocument();
			JavaSource javaSource = JavaSource.forDocument(doc);
			Task task = new Task();
			ModificationResult result = javaSource.runModificationTask(task);
			result.commit();
			while (task.executeNextTask()) {
				break;
			}
		}
		catch (Exception ex) {
			ex.printStackTrace();
		}
	}

	private class Task implements CancellableTask<WorkingCopy> {
		private IdentifierTree jmeImporterIdentifier;
		private IdentifierTree jmeExporterIdentifier;
		private TreeMaker make;
		private TypeElement typeElementInputCapsule;
		private TypeElement typeElementOutputCapsule;
		private TypeElement typeElementSavable;
		private static final String capsuleIdentifier = "capsule";
		private WorkingCopy workingCopy;
		
		private ConverterGenerator converterGenerator;
		
		final HashMap<Name,ElementHandle<TypeElement>> classProcessedStates;
		final ElementHandle<TypeElement> targetTypeElementHandle;
		
		public Task() {
			this.classProcessedStates = new HashMap<Name, ElementHandle<TypeElement>>();
			this.targetTypeElementHandle = null;
		}

		public Task(HashMap<Name,ElementHandle<TypeElement>> classProcessedStates, ElementHandle<TypeElement> targetType) {
			this.classProcessedStates = classProcessedStates;
			this.targetTypeElementHandle = targetType;
		}

		private void setupForModification() throws IOException {
			workingCopy.toPhase(JavaSource.Phase.RESOLVED);
			make = workingCopy.getTreeMaker();
			
			converterGenerator = new ConverterGenerator(workingCopy, make);
			
			final Elements elements = workingCopy.getElements();
			typeElementInputCapsule = elements.getTypeElement("com.jme3.export.InputCapsule");
			typeElementOutputCapsule = elements.getTypeElement("com.jme3.export.OutputCapsule");
			typeElementSavable = elements.getTypeElement("com.jme3.export.Savable");
		}

		boolean executeNextTask() throws IOException {
			//Execute next task
			for (Map.Entry<Name,ElementHandle<TypeElement>> entry : classProcessedStates.entrySet()) {
				if (entry.getValue() != null) {
					if (LOG >= 1) System.out.println("Attempting to process another task for " + entry.getKey());
					
					final FileObject fileObject = SourceUtils.getFile(entry.getValue(), workingCopy.getClasspathInfo());
					if (fileObject == null) {
						if (LOG >= 1) System.out.println("Couldn't find FileObject for " + entry.getValue());
						continue;
					}
					
					final DataObject dataObject = DataObject.find(fileObject);
					if (dataObject == null) {
						if (LOG >= 1) System.out.println("Couldn't find DataObject for " + fileObject);
						continue;
					}
					
					EditorCookie editCookie = dataObject.getCookie(EditorCookie.class);
					if (editCookie == null) {
						if (LOG >= 1) System.out.println("Couldn't find EditCookie for " + dataObject);
						continue;
					}
					StyledDocument document = editCookie.openDocument();
					if (document == null) {
						if (LOG >= 1) System.out.println("Failed to open document for " + editCookie);
						continue;
					}
					editCookie.open();
					JavaSource javaSource = JavaSource.forDocument(document);
					if (javaSource == null) {
						if (LOG >= 1) System.out.println("Failed to get JavaSource for " + document);
						continue;
					}
					CancellableTask<WorkingCopy> task = new Task(classProcessedStates, entry.getValue());
					if (LOG >= 3) System.out.println("Task=" + task);
					ModificationResult result = javaSource.runModificationTask(task);
					if (LOG >= 3) System.out.println("result=" + result);
					result.commit();
					if (LOG >= 3) System.out.println("result=" + result + " done");
					return true;
				}
			}
			return false;
		}

		private class ExpandFailException extends IllegalStateException {
			public ExpandFailException(String s) {
				super(s);
			}
		}

		@Override
		public void run(WorkingCopy workingCopyIn) throws IOException {
			this.workingCopy = workingCopyIn;
			setupForModification();

			if (targetTypeElementHandle == null) {
				//First pass is with empty hashtable - take elements from cursor position
				TreePath currentPath = workingCopy.getTreeUtilities().pathFor(textComp.getCaretPosition());
				for (; currentPath != null ; currentPath = currentPath.getParentPath()) {
					if (LOG >= 3) System.out.println("Walking " + currentPath + " leaf=" + currentPath.getLeaf().getKind());
					if (currentPath.getLeaf() instanceof ClassTree) {
						processClass(currentPath);
						break;
					}
				}
			}
			else {
				for (Map.Entry<Name,ElementHandle<TypeElement>> entry : classProcessedStates.entrySet()) {
					if (LOG >= 3) System.out.println("Checking " + entry.getKey() + " value=" + entry.getValue());
					if (entry.getValue() != null) {
						TypeElement element = entry.getValue().resolve(workingCopy);
						if (LOG >= 3) System.out.println("Element: " + element);
						entry.setValue(null);
						final TreePath path = workingCopy.getTrees().getPath(element);
						if (LOG >= 3) System.out.println("Path: " + path + " leaf = " + path.getLeaf());
						if (path.getLeaf() instanceof ClassTree) {
							processClass(path);
							break;
						}
					}
				}
			}
		}
		
		@Override
		public void cancel() {
		}

		private void processClass(TreePath classTreePath) {
			ClassTree classTree = (ClassTree) classTreePath.getLeaf();
			ClassTree modifiedClassTree = classTree;
			
			TypeElement classTypeElement = (TypeElement) workingCopy.getTrees().getElement(classTreePath);
			
			if (targetTypeElementHandle == null) {
				classProcessedStates.put(classTypeElement.getQualifiedName(), null);
			}
			
			final Elements elements = workingCopy.getElements();
			CompilationUnitTree cut = workingCopy.getCompilationUnit();
			
			if (!workingCopy.getTypes().isSubtype(classTypeElement.asType(), typeElementSavable.asType())) {
				modifiedClassTree = make.addClassImplementsClause(modifiedClassTree, make.Identifier(typeElementSavable));
				workingCopy.rewrite(classTree, modifiedClassTree);
				classTree = modifiedClassTree;
			}
			
			StringBuilder readMethodContent = new StringBuilder();
			jmeImporterIdentifier = make.Identifier("im");
			jmeExporterIdentifier = make.Identifier("ex");
			
			List<StatementTree> readBody = new ArrayList<StatementTree>();
			List<StatementTree> writeBody = new ArrayList<StatementTree>();
			
			
			readBody.add(
					make.Variable(
						converterGenerator.finalModifiers,
						capsuleIdentifier,
						make.QualIdent(typeElementInputCapsule),
						make.MethodInvocation(
							Collections.<ExpressionTree>emptyList(),
							make.MemberSelect(jmeImporterIdentifier, "getCapsule"),
							Collections.singletonList(make.Identifier("this")))));
			
			writeBody.add(
					make.Variable(
						converterGenerator.finalModifiers,
						capsuleIdentifier,
						make.QualIdent(typeElementOutputCapsule),
						make.MethodInvocation(
							Collections.<ExpressionTree>emptyList(),
							make.MemberSelect(jmeExporterIdentifier, "getCapsule"),
							Collections.singletonList(make.Identifier("this")))));
			
			ExecutableElement existingWrite = null;
			ExecutableElement existingRead = null;
			boolean makeWrite = true;
			boolean makeRead = true;
			
			for (Element savableMember : workingCopy.getElements().getAllMembers(typeElementSavable)) {
				if (savableMember instanceof ExecutableElement) {
					final ExecutableElement savableMethod = (ExecutableElement)savableMember;
					final Element implementation = workingCopy.getElementUtilities().getImplementationOf(savableMethod, classTypeElement);
					if (implementation instanceof ExecutableElement) {
						
						SerializerMethodConfig config = implementation.getAnnotation(SerializerMethodConfig.class);
						if (savableMethod.getSimpleName().contentEquals("read")) {
							existingRead = (ExecutableElement) implementation;
							makeRead = config != null && config.autoGenerated();
						}
						else if (savableMethod.getSimpleName().contentEquals("write")) {
							existingWrite = (ExecutableElement) implementation;
							makeWrite = config != null && config.autoGenerated();
						}
					}
				}
			}
			
			for (Tree member : modifiedClassTree.getMembers()) {
				if (member.getKind() == Tree.Kind.VARIABLE) {
					final VariableTree memberVariable = (VariableTree) member;
					if (!memberVariable.getModifiers().getFlags().contains(Modifier.STATIC)) {
						try {
							processField(classTypeElement, memberVariable, writeBody, readBody);
						}
						catch (Exception e) {
							final String message = "Failed to make read/write for " + memberVariable.toString() + ": " + e.getMessage();
							make.addComment(writeBody.get(writeBody.size() - 1), Comment.create(Comment.Style.BLOCK, message), false);
							make.addComment(readBody.get(readBody.size() - 1), Comment.create(Comment.Style.BLOCK, message), false);
							e.printStackTrace(System.out);
						}
					}
				}
				else {
					if (LOG >= 2) System.out.println("Ignoring member kind=" + member.getKind());
					if (LOG >= 3) System.out.println("\tclass=" + member.getClass().getCanonicalName());
					//							System.out.println("\tclass=" + member.getClass().getInterfaces());
				}
			}
			
			final AnnotationTree autoGeneratedAnnotation =
					make.Annotation(make.Type(workingCopy.getElements().getTypeElement("com.wivlaro.jme3.export.SerializerMethodConfig").asType()),
									Arrays.asList(make.Assignment(make.Identifier("autoGenerated"),
																  make.Literal(true))));
			ModifiersTree methodModifiers =
					make.Modifiers(Collections.<Modifier>singleton(Modifier.PUBLIC),
								   Arrays.asList(autoGeneratedAnnotation));
			
			TypeElement element = elements.getTypeElement("java.io.IOException");
			ExpressionTree throwsClause = make.QualIdent(element);
			
			if (makeWrite) {
				VariableTree writeParameter =
						make.Variable(
						converterGenerator.finalModifiers,
						"ex",
						make.QualIdent(elements.getTypeElement("com.jme3.export.JmeExporter")),
						null);
				
				MethodTree newMethod = make.Method(methodModifiers,
												   "write",
												   make.PrimitiveType(TypeKind.VOID),
												   Collections.<TypeParameterTree>emptyList(),
												   Collections.singletonList(writeParameter),
												   Collections.<ExpressionTree>singletonList(throwsClause),
												   make.Block(writeBody, false),
												   null);
				if (existingWrite != null) {
					modifiedClassTree = make.removeClassMember(modifiedClassTree, workingCopy.getTrees().getTree(existingWrite));
				}
				modifiedClassTree = make.addClassMember(modifiedClassTree, newMethod);
			}
			if (makeRead) {
				VariableTree readParameter =
						make.Variable(
						converterGenerator.finalModifiers,
						jmeImporterIdentifier.getName(),
						make.QualIdent(elements.getTypeElement("com.jme3.export.JmeImporter")),
						null);
				MethodTree newMethod = make.Method(methodModifiers,
												   "read",
												   make.PrimitiveType(TypeKind.VOID),
												   Collections.<TypeParameterTree>emptyList(),
												   Collections.singletonList(readParameter),
												   Collections.<ExpressionTree>singletonList(throwsClause), make.Block(readBody, false),
												   null);
				if (existingRead != null) {
					modifiedClassTree = make.removeClassMember(modifiedClassTree,  workingCopy.getTrees().getTree(existingRead));
				}
				modifiedClassTree = make.addClassMember(modifiedClassTree, newMethod);
			}
			
			if (modifiedClassTree != classTree) {
				workingCopy.rewrite(classTree, modifiedClassTree);
			}
		}
		

		private void processField(final TypeElement classTypeElement, final VariableTree memberVariable, List<StatementTree> writeBody, List<StatementTree> readBody) throws IllegalStateException {
			final Elements elements = workingCopy.getElements();

			final TreePath memberTreePath = workingCopy.getTrees().getPath(workingCopy.getCompilationUnit(), memberVariable);
			final Element memberElement = workingCopy.getTrees().getElement(memberTreePath);
			final ExpressionTree memberLocation = make.MemberSelect(make.Identifier("this"), memberElement);
			final TypeMirror memberType = memberElement.asType();
			
			String typeSuffix = null;
			CharSequence storageLabel = memberElement.getSimpleName();
			
			SerializeFieldConfig fieldInfo = memberElement.getAnnotation(SerializeFieldConfig.class);
			if (fieldInfo != null) {
				if (!fieldInfo.serialize()) {
					return;
				}
				if (fieldInfo.storageLabel() != null && !fieldInfo.storageLabel().isEmpty()) {
					storageLabel = fieldInfo.storageLabel();
				}
				typeSuffix = fieldInfo.typeSuffix();
			}
			
			if (typeSuffix == null || typeSuffix.isEmpty()) {
				typeSuffix = calculateTypeSuffix(memberType);
			}

			if (typeSuffix == null) {
				throw new ExpandFailException("Failed to find type suffix");
			}

			String writeName = "write";
			ExecutableElement writeMethod = converterGenerator.getMethod(typeElementOutputCapsule, writeName + typeSuffix);
			if (writeMethod != null) {
				writeName += typeSuffix;
			}
			else {
				writeMethod = converterGenerator.getMethod(typeElementOutputCapsule, writeName);
			}

			if (writeMethod == null) {
				throw new ExpandFailException("Couldn't find write method: " + writeName + " type suffix: " + typeSuffix);
			}

			String readName = "read" + typeSuffix;
			final ExecutableElement readMethod =  converterGenerator.getMethod(typeElementInputCapsule, readName);
			if (readMethod == null) {
				throw new ExpandFailException("Couldn't find readMethod: " + readName);
			}
			TypeMirror storedType = readMethod.getReturnType();

			ExpressionTree defaultValue = memberVariable.getInitializer();
			if (defaultValue == null || !workingCopy.getTypes().isAssignable(memberType, storedType)) {
				defaultValue = makeDefaultValue(storedType);
			}

			final LiteralTree fieldLiteral = make.Literal(storageLabel.toString());

			ExpressionTree readExpression;
			
			//Enum has special arguments
			if (typeSuffix.equals("Enum")) {
				readExpression = make.MethodInvocation(
						Collections.<ExpressionTree>emptyList(),
						make.MemberSelect(make.Identifier(capsuleIdentifier), readName),
						Arrays.asList(
							fieldLiteral,
							make.MemberSelect(make.Identifier(workingCopy.getTypeUtilities().getTypeName(memberType, TypeUtilities.TypeNameOptions.PRINT_FQN)),
											  "class"),
							defaultValue));
				storedType = memberType;
			}
			else {
				readExpression = make.MethodInvocation(
						Collections.<ExpressionTree>emptyList(),
						make.MemberSelect(make.Identifier(capsuleIdentifier), readName),
						Arrays.asList(fieldLiteral, defaultValue));
			}

			
			writeBody.add(make.ExpressionStatement(
					make.MethodInvocation(
						Collections.<ExpressionTree>emptyList(),
						make.MemberSelect(make.Identifier(capsuleIdentifier), writeName),
						Arrays.asList(
							converterGenerator.generateConverterExpression(writeBody, memberType, memberLocation, storedType),
							fieldLiteral,
							defaultValue))));
			
			if (fieldInfo != null && fieldInfo.setMethod() != null && !fieldInfo.setMethod().isEmpty()) {
				readBody.add(
					make.ExpressionStatement(
							make.MethodInvocation(
								Arrays.<ExpressionTree>asList(),
								make.Identifier(memberElement),
								Arrays.asList(converterGenerator.generateConverterExpression(readBody, storedType, readExpression, memberType)))));
			}
			else {
				readBody.add(converterGenerator.generateConverterAssignment(storedType, readExpression, memberType, memberLocation));
			}
		}
		

		private String calculateTypeSuffix(final TypeMirror type) {
			//Control which read method to use, and whether we can serialize
			String typeSuffix = null;
			if (type.getKind() == TypeKind.ARRAY) {
				final ArrayType arrayType = (ArrayType)type;
				final TypeMirror componentType = arrayType.getComponentType();
				
				if (componentType.getKind() == TypeKind.ARRAY) {
					final ArrayType componentArrayType = (ArrayType)componentType;
					final TypeMirror componentComponentType = componentArrayType.getComponentType();
					typeSuffix = calculateTypeSuffix(componentComponentType) + "Array2D";
				}
				else {
					typeSuffix = calculateTypeSuffix(componentType) + "Array";
				}
			}
			else if (type.getKind() == TypeKind.DECLARED) {
				final Types types = workingCopy.getTypes();
				final Elements elements = workingCopy.getElements();
				final DeclaredType declaredType = (DeclaredType) type;
				DeclaredType wantedType;
				
				if (converterGenerator.getTypeAs(declaredType, "com.jme3.export.Savable") != null) {
					final TypeElement savableClass = (TypeElement)declaredType.asElement();
					if (!savableClass.getQualifiedName().contentEquals("com.jme3.export.Savable")) {
						ElementHandle<TypeElement> handle = ElementHandle.create(savableClass);
						if (LOG >= 2) System.out.println("Adding handle to be processed: " + savableClass.getQualifiedName() + " -> " + handle);
						if (!classProcessedStates.containsKey(savableClass.getQualifiedName())) {
							classProcessedStates.put(savableClass.getQualifiedName(), handle);
						}
					}
					typeSuffix = "Savable";
				}
				else if (converterGenerator.getTypeAs(declaredType, "java.util.BitSet") != null) {
					typeSuffix = "BitSet";
				}
				else if (converterGenerator.getTypeAs(declaredType, "java.nio.DoubleBuffer") != null) {
					typeSuffix = "DoubleBuffer";
				}
				else if (converterGenerator.getTypeAs(declaredType, "java.nio.FloatBuffer") != null) {
					typeSuffix = "FloatBuffer";
				}
				else if (converterGenerator.getTypeAs(declaredType, "java.nio.IntBuffer") != null) {
					typeSuffix = "IntBuffer";
				}
				else if (converterGenerator.getTypeAs(declaredType, "java.nio.ShortBuffer") != null) {
					typeSuffix = "ShortBuffer";
				}
				else if (converterGenerator.getTypeAs(declaredType, "java.nio.ByteBuffer") != null) {
					typeSuffix = "ByteBuffer";
				}
				else if (converterGenerator.getTypeAs(declaredType, "java.lang.String") != null) {
					typeSuffix = "String";
				}
				else if (converterGenerator.getTypeAs(declaredType, "java.lang.Enum") != null) {
					typeSuffix = "Enum";
				}
				else if ((wantedType = converterGenerator.getTypeAs(declaredType, "com.jme3.util.IntMap")) != null) {
					final List<? extends TypeMirror> typeParameters = wantedType.getTypeArguments();
					if (typeParameters != null && !typeParameters.isEmpty()) {
						TypeMirror componentType = typeParameters.get(0);
						typeSuffix = "Int" + calculateTypeSuffix(componentType) + "Map";
					}
					else {
						//Don't know what's in it, assume Savables
						typeSuffix = "IntSavableMap";
					}
				}
				else if ((wantedType = converterGenerator.getTypeAs(declaredType, "java.util.ArrayList")) != null) {
					final List<? extends TypeMirror> typeParameters = wantedType.getTypeArguments();
					if (typeParameters != null && !typeParameters.isEmpty()) {
						TypeMirror componentType = typeParameters.get(0);
						typeSuffix = calculateTypeSuffix(componentType) + "ArrayList";
					}
					else {
						//Don't know what's in it, assume Savables
						typeSuffix = "SavableArrayList";
					}
				}
				else if ((wantedType = converterGenerator.getTypeAs(declaredType, "java.util.Map")) != null) {
					final List<? extends TypeMirror> typeParameters = wantedType.getTypeArguments();
					if (typeParameters != null && !typeParameters.isEmpty()) {
						final String keySuffix = calculateTypeSuffix(typeParameters.get(0));
						final String valueSuffix = calculateTypeSuffix(typeParameters.get(1));
						if (keySuffix.equals("Savable") && valueSuffix.equals("Savable")) {
							typeSuffix = valueSuffix + "Map";
						}
						else {
							typeSuffix = keySuffix + valueSuffix + "Map";
						}
					}
					else {
						//Don't know what's in it, assume Savables
						typeSuffix = "SavableMap";
					}
				}
			}
			else if (type.getKind() == TypeKind.BOOLEAN) {
				typeSuffix = "Boolean";
			}
			else if (type.getKind() == TypeKind.BYTE) {
				typeSuffix = "Byte";
			}
			else if (type.getKind() == TypeKind.SHORT) {
				typeSuffix = "Short";
			}
			else if (type.getKind() == TypeKind.INT) {
				typeSuffix = "Int";
			}
			else if (type.getKind() == TypeKind.LONG) {
				typeSuffix = "Long";
			}
			else if (type.getKind() == TypeKind.FLOAT) {
				typeSuffix = "Float";
			}
			else if (type.getKind() == TypeKind.DOUBLE) {
				typeSuffix = "Double";
			}
			return typeSuffix;
		}

		private ExpressionTree makeDefaultValue(final TypeMirror memberType) {
			ExpressionTree defaultValue = null;
			if (memberType.getKind().isPrimitive()) {
				switch (memberType.getKind())
				{
					case BOOLEAN: defaultValue = make.Literal(false); break;
					case BYTE: defaultValue = make.TypeCast(make.PrimitiveType(TypeKind.BYTE), make.Literal(0)); break;
					case SHORT: defaultValue = make.TypeCast(make.PrimitiveType(TypeKind.SHORT), make.Literal(0)); break;
					case INT: defaultValue = make.Literal(0); break;
					case LONG: defaultValue = make.Literal(0L); break;
					case CHAR: defaultValue = make.Literal('\0'); break;
					case FLOAT: defaultValue = make.Literal(0.0f); break;
					case DOUBLE: defaultValue = make.Literal(0.0); break;
				}
			}
			else {
				defaultValue = make.Literal(null);
			}
			return defaultValue;
		}
	}
}

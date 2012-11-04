package com.wivlaro.netbeans.serializergenerator;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.ArrayTypeTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.ParameterizedTypeTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.TypeParameterTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import java.io.IOException;
import java.lang.annotation.ElementType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.TypeParameterElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.ReferenceType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.swing.text.Document;
import javax.swing.text.JTextComponent;
import org.netbeans.api.editor.mimelookup.MimeRegistration;
import org.netbeans.api.java.source.CancellableTask;
import org.netbeans.api.java.source.Comment;
import org.netbeans.api.java.source.JavaSource;
import org.netbeans.api.java.source.ModificationResult;
import org.netbeans.api.java.source.TreeMaker;
import org.netbeans.api.java.source.TypeUtilities;
import org.netbeans.api.java.source.WorkingCopy;
import org.netbeans.spi.editor.codegen.CodeGenerator;
import org.netbeans.spi.editor.codegen.CodeGeneratorContextProvider;
import org.openide.util.Lookup;

public class SavableGenerator implements CodeGenerator {

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
			CancellableTask<WorkingCopy> task = new Task();
			ModificationResult result = javaSource.runModificationTask(task);
			result.commit();
		}
		catch (Exception ex) {
			ex.printStackTrace();
		}
	}

	private static class Task implements CancellableTask<WorkingCopy> {
		private IdentifierTree jmeImporterIdentifier;
		private IdentifierTree jmeExporterIdentifier;
		private TreeMaker make;
		private TypeElement typeElementInputCapsule;
		private TypeElement typeElementOutputCapsule;
		private TypeElement typeElementSavable;
		private static final String capsuleIdentifier = "capsule";
		private TypeMirror arrayListType;
		private ModifiersTree finalModifiers;
		private WorkingCopy workingCopy;

		private int tempCounter = 0;
		private ModifiersTree emptyModifiers;
		private String makeTempName(Object inspiration) {
			return "temp" + (++tempCounter) + "_" + inspiration.toString().replaceAll("[^a-zA-Z0-9]+", "_").replaceFirst("_$", "");
		}

		private class ExpandFailException extends IllegalStateException {

			public ExpandFailException(String s) {
				super(s);
			}
		}

		@Override
		public void run(WorkingCopy workingCopyIn) throws IOException {
			this.workingCopy = workingCopyIn;
			workingCopy.toPhase(JavaSource.Phase.RESOLVED);
			CompilationUnitTree cut = workingCopy.getCompilationUnit();
			make = workingCopy.getTreeMaker();
			finalModifiers = make.Modifiers(Collections.<Modifier>singleton(Modifier.FINAL), Collections.<AnnotationTree>emptyList());
			emptyModifiers = make.Modifiers(Collections.<Modifier>emptySet(), Collections.<AnnotationTree>emptyList());
			
			final Elements elements = workingCopy.getElements();
			typeElementInputCapsule = elements.getTypeElement("com.jme3.export.InputCapsule");
			typeElementOutputCapsule = elements.getTypeElement("com.jme3.export.OutputCapsule");
			typeElementSavable = elements.getTypeElement("com.jme3.export.Savable");
			arrayListType = elements.getTypeElement("java.util.ArrayList").asType();

			for (Tree typeDecl : cut.getTypeDecls()) {
//				System.out.println("Type declaration: "+typeDecl);
				if (Tree.Kind.CLASS == typeDecl.getKind()) {
					ClassTree clazz = (ClassTree) typeDecl;
					ClassTree modifiedClazz = clazz;

					final String className = cut.getPackageName() + "." + modifiedClazz.getSimpleName();
					System.out.println("class name: " + className);
					final TypeElement classTypeElement = elements.getTypeElement(className);

					if (!workingCopy.getTypes().isSubtype(classTypeElement.asType(), typeElementSavable.asType())) {
						modifiedClazz = make.addClassImplementsClause(modifiedClazz, make.Identifier(typeElementSavable));
						workingCopy.rewrite(clazz, modifiedClazz);
						clazz = modifiedClazz;
					}

					StringBuilder readMethodContent = new StringBuilder();
					jmeImporterIdentifier = make.Identifier("im");
					jmeExporterIdentifier = make.Identifier("ex");

					List<StatementTree> readBody = new ArrayList<StatementTree>();
					List<StatementTree> writeBody = new ArrayList<StatementTree>();


					readBody.add(
							make.Variable(
							finalModifiers,
							capsuleIdentifier,
							make.QualIdent(typeElementInputCapsule),
							make.MethodInvocation(
							Collections.<ExpressionTree>emptyList(),
							make.MemberSelect(jmeImporterIdentifier, "getCapsule"),
							Collections.singletonList(
							make.Identifier("this")))));

					writeBody.add(
							make.Variable(
							finalModifiers,
							capsuleIdentifier,
							make.QualIdent(typeElementOutputCapsule),
							make.MethodInvocation(
							Collections.<ExpressionTree>emptyList(),
							make.MemberSelect(jmeExporterIdentifier, "getCapsule"),
							Collections.singletonList(
							make.Identifier("this")))));

					boolean hasRead = false;
					boolean hasWrite = false;

					for (Tree member : modifiedClazz.getMembers()) {
						if (member.getKind() == Tree.Kind.METHOD) {
							MethodTree methodTree = (MethodTree) member;
							if (methodTree.getName().contentEquals("read")) {
								hasRead = true;
							}
							else if (methodTree.getName().contentEquals("write")) {
								hasWrite = true;
							}
						}
						else if (member.getKind() == Tree.Kind.VARIABLE) {
							final VariableTree memberVariable = (VariableTree) member;
							if (!memberVariable.getModifiers().getFlags().contains(Modifier.STATIC)) {
								try {
									processField(classTypeElement, memberVariable, writeBody, readBody);
								}
								catch (ExpandFailException e) {
									final String message = "Failed to make read/write for " + memberVariable.toString() + ": " + e.getMessage();
									make.addComment(writeBody.get(writeBody.size() - 1), Comment.create(Comment.Style.LINE, message), false);
									make.addComment(readBody.get(readBody.size() - 1), Comment.create(Comment.Style.LINE, message), false);
								}
								catch (Exception e) {
									final String message = "Failed to make read/write for " + memberVariable.toString() + ": " + e.getMessage();
									make.addComment(writeBody.get(writeBody.size() - 1), Comment.create(Comment.Style.LINE, message), false);
									make.addComment(readBody.get(readBody.size() - 1), Comment.create(Comment.Style.LINE, message), false);
									e.printStackTrace(System.out);
								}
							}
						}
						else {
							System.out.println("Ignoring member kind=" + member.getKind());
							System.out.println("\tclass=" + member.getClass().getCanonicalName());
//							System.out.println("\tclass=" + member.getClass().getInterfaces());
						}
					}

					ModifiersTree methodModifiers =
							make.Modifiers(Collections.<Modifier>singleton(Modifier.PUBLIC),
										   Collections.<AnnotationTree>emptyList());

					TypeElement element = elements.getTypeElement("java.io.IOException");
					ExpressionTree throwsClause = make.QualIdent(element);

					if (!hasWrite) {
						VariableTree writeParameter =
								make.Variable(
								finalModifiers,
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
						modifiedClazz = make.addClassMember(modifiedClazz, newMethod);
					}
					if (!hasRead) {
						VariableTree readParameter =
								make.Variable(
								finalModifiers,
								jmeImporterIdentifier.getName(),
								make.QualIdent(elements.getTypeElement("com.jme3.export.JmeImporter")),
								null);

						MethodTree newMethod = make.Method(methodModifiers,
														   "read",
														   make.PrimitiveType(TypeKind.VOID),
														   Collections.<TypeParameterTree>emptyList(),
														   Collections.singletonList(readParameter),
														   Collections.<ExpressionTree>singletonList(throwsClause),
														   make.Block(readBody, false),
														   null);
						modifiedClazz = make.addClassMember(modifiedClazz, newMethod);
					}

					if (modifiedClazz != clazz) {
						workingCopy.rewrite(clazz, modifiedClazz);
					}
				}
			}
		}

		@Override
		public void cancel() {
		}
		

		private void processField(final TypeElement classTypeElement, final VariableTree memberVariable, List<StatementTree> writeBody, List<StatementTree> readBody) throws IllegalStateException {
			final Elements elements = workingCopy.getElements();

			final TreePath memberTreePath = workingCopy.getTrees().getPath(workingCopy.getCompilationUnit(), memberVariable);
			final Element memberElement = workingCopy.getTrees().getElement(memberTreePath);
			final ExpressionTree memberLocation = make.MemberSelect(make.Identifier("this"), memberElement);
			final TypeMirror memberType = memberElement.asType();

			String typeSuffix = calculateTypeSuffix(memberType);

			if (typeSuffix == null) {
				throw new ExpandFailException("Failed to find type suffix");
			}

			String writeName = "write";
			ExecutableElement writeMethod = getMethod(typeElementOutputCapsule, writeName + typeSuffix);
			if (writeMethod != null) {
				writeName += typeSuffix;
			}
			else {
				writeMethod = getMethod(typeElementOutputCapsule, writeName);
			}

			if (writeMethod == null) {
				throw new ExpandFailException("Couldn't find write method: " + writeName + " type suffix: " + typeSuffix);
			}

			String readName = "read" + typeSuffix;
			final ExecutableElement readMethod = getMethod(typeElementInputCapsule, readName);
			if (readMethod == null) {
				throw new ExpandFailException("Couldn't find readMethod: " + readName);
			}
			TypeMirror inputType = readMethod.getReturnType();

			
			ExpressionTree defaultValue = memberVariable.getInitializer();
			if (defaultValue == null) {
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
			}

			final LiteralTree fieldLiteral = make.Literal(memberVariable.getName().toString());
			
			writeBody.add(make.ExpressionStatement(
					make.MethodInvocation(
					Collections.<ExpressionTree>emptyList(),
					make.MemberSelect(make.Identifier(capsuleIdentifier), writeName),
					Arrays.asList(
						make.Identifier(memberVariable.getName().toString()),
						fieldLiteral,
						defaultValue))));

			ExpressionTree inputExpression;
			
			//Enum has special arguments
			if (typeSuffix.equals("Enum")) {
				inputExpression = make.MethodInvocation(
						Collections.<ExpressionTree>emptyList(),
						make.MemberSelect(make.Identifier(capsuleIdentifier), readName),
						Arrays.asList(
							fieldLiteral,
							make.MemberSelect(make.Identifier(workingCopy.getTypeUtilities().getTypeName(memberType, TypeUtilities.TypeNameOptions.PRINT_FQN)),
											  "class"),
							defaultValue));
				inputType = memberType;
			}
			else {
				inputExpression = make.MethodInvocation(
						Collections.<ExpressionTree>emptyList(),
						make.MemberSelect(make.Identifier(capsuleIdentifier), readName),
						Arrays.asList(fieldLiteral,defaultValue));
			}
			readBody.add(generateConverter(inputType, inputExpression, memberType, memberLocation));
		}

		private StatementTree generateConverter(final TypeMirror inputType, ExpressionTree inputExpression, final TypeMirror outputType, final ExpressionTree outputLocation) throws ExpandFailException {
			System.out.println("generateConverter: " + inputType + " to " + outputType);
			final Types types = workingCopy.getTypes();
			//Trivially assignable
			if (types.isAssignable(inputType, outputType)) {
				return generateTrivialConverter(inputType, inputExpression, outputType, outputLocation);
			}
			
			TypeMirror inputArrayComponentType = null;
			TypeMirror outputArrayComponentType = null;
			
			if (inputType.getKind() == TypeKind.ARRAY) {
				inputArrayComponentType = ((ArrayType) inputType).getComponentType();
			}
			else if (types.isSubtype(inputType, arrayListType)) {
				
			}
			
			if (outputType.getKind() == TypeKind.ARRAY) {
				outputArrayComponentType = ((ArrayType) outputType).getComponentType();
			}
			else if (types.isSubtype(outputType, arrayListType)) {
				
			}
			
			if (inputArrayComponentType != null && outputArrayComponentType != null) {
				ArrayList<StatementTree> readCommands = new ArrayList<StatementTree>(2);
				inputExpression = onceOnly(readCommands, inputExpression, inputType);

				List<StatementTree> copyBlock = new ArrayList<StatementTree>();
				
				copyBlock.add(make.ExpressionStatement(
								make.Assignment(
									outputLocation,
									make.NewArray(
										make.Type(outputArrayComponentType),
										Arrays.<ExpressionTree>asList(make.MemberSelect(inputExpression, "length")),
										null))));
				
				final String inputElement = makeTempName(inputArrayComponentType);
//				make.EnhancedForLoop(
//						make.Variable(emptyModifiers, inputElement, make.Type(inputArrayComponentType), null),
//						inputExpression,
//						
//						)

				//Also an array?
				if (types.isAssignable(inputArrayComponentType, outputArrayComponentType)) {
					copyBlock.add(
						make.ExpressionStatement(
							make.MethodInvocation(
								Collections.<ExpressionTree>emptyList(),
								make.MemberSelect(
									make.Identifier(workingCopy.getElements().getTypeElement("java.lang.System")),
									"arraycopy"),
								Arrays.asList(inputExpression, make.Literal(0), outputLocation, make.Literal(0), make.MemberSelect(inputExpression, "length")))));
				}
				else {
					final IdentifierTree indexIdentifier = make.Identifier(makeTempName("Index"));
					copyBlock.add(
						make.ForLoop(
							Arrays.asList(make.Variable(emptyModifiers,
														indexIdentifier.getName(), 
														make.PrimitiveType(TypeKind.INT),
														make.Literal(0))),
							make.Binary(Tree.Kind.LESS_THAN, indexIdentifier, make.MemberSelect(inputExpression, "length")),
							Arrays.asList(make.ExpressionStatement(make.Unary(Tree.Kind.POSTFIX_INCREMENT, indexIdentifier))),
							generateConverter(inputArrayComponentType, make.ArrayAccess(inputExpression, indexIdentifier), outputArrayComponentType, make.ArrayAccess(outputLocation, indexIdentifier))));
				}

				readCommands.add(
					make.If(
						make.Binary(Tree.Kind.NOT_EQUAL_TO, inputExpression, make.Literal(null)),
						make.Block(copyBlock, false),
						make.ExpressionStatement(make.Assignment(outputLocation, make.Literal(null)))));
				return make.Block(readCommands, false);
			}
			
			if (types.isSubtype(inputType, arrayListType) && types.isSubtype(outputType, arrayListType)) {
				throw new ExpandFailException("Array list not done yet");
			}
			
			return generateTrivialConverter(inputType, inputExpression, outputType, outputLocation);
		}

		private StatementTree generateTrivialConverter(final TypeMirror inputType, ExpressionTree inputExpression, final TypeMirror outputType, final ExpressionTree outputLocation) {
			if (!workingCopy.getTypes().isAssignable(inputType, outputType)) {
				inputExpression = make.TypeCast(make.Type(outputType), inputExpression);
			}
			return make.ExpressionStatement(make.Assignment(outputLocation, inputExpression));
		}
		

//		private String calculateTypeSuffix(final VariableTree memberVariable, final TypeElement classTypeElement) {
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
				
				if (getTypeAs(declaredType, "com.jme3.export.Savable") != null) {
					typeSuffix = "Savable";
				}
				else if (getTypeAs(declaredType, "java.util.BitSet") != null) {
					typeSuffix = "BitSet";
				}
				else if (getTypeAs(declaredType, "java.nio.DoubleBuffer") != null) {
					typeSuffix = "DoubleBuffer";
				}
				else if (getTypeAs(declaredType, "java.nio.FloatBuffer") != null) {
					typeSuffix = "FloatBuffer";
				}
				else if (getTypeAs(declaredType, "java.nio.IntBuffer") != null) {
					typeSuffix = "IntBuffer";
				}
				else if (getTypeAs(declaredType, "java.nio.ShortBuffer") != null) {
					typeSuffix = "ShortBuffer";
				}
				else if (getTypeAs(declaredType, "java.nio.ByteBuffer") != null) {
					typeSuffix = "ByteBuffer";
				}
				else if (getTypeAs(declaredType, "java.lang.String") != null) {
					typeSuffix = "String";
				}
				else if (getTypeAs(declaredType, "java.lang.Enum") != null) {
					typeSuffix = "Enum";
				}
				else if (getTypeAs(declaredType, "com.jme3.util.IntMap") != null) {
					typeSuffix = "IntSavableMap";
				}
				else if ((wantedType = getTypeAs(declaredType, "java.util.ArrayList")) != null) {
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
				else if ((wantedType = getTypeAs(declaredType, "java.util.Map")) != null) {
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
		
		private DeclaredType getTypeAs(DeclaredType type, CharSequence name) {
			final Types types = workingCopy.getTypes();
			final Element element = types.asElement(type);
			
			if (element instanceof TypeElement) {
				final TypeElement typeElement = (TypeElement)element;
				if (typeElement.getQualifiedName().contentEquals(name)) {
					return type;
				}
			}
			
			for (TypeMirror supertype : types.directSupertypes(type)) {
				final DeclaredType t = getTypeAs((DeclaredType)supertype, name);
				if (t != null) {
					return t;
				}
			}
			return null;
		}

		private ExecutableElement getMethod(TypeElement parent, CharSequence name) {
			final Elements elements = workingCopy.getElements();
			for (Element member : elements.getAllMembers(parent)) {
				if (member.getKind() == ElementKind.METHOD && member.getSimpleName().contentEquals(name)) {
					return (ExecutableElement) member;
				}
			}
			return null;
		}


		private ExpressionTree onceOnly(List<StatementTree> statements, ExpressionTree input, final TypeMirror inputType) {
			if (!(input instanceof IdentifierTree)) {
				final String tempName = makeTempName(inputType.toString().replaceFirst("^([a-zA-Z0-9]+\\.)+", ""));
				statements.add(make.Variable(finalModifiers, tempName, make.Type(inputType), input));
				input = make.Identifier(tempName);
			}
			return input;
		}
	}
}

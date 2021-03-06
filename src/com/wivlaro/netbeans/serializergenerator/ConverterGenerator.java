/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.wivlaro.netbeans.serializergenerator;

import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.IfTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.ModifiersTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import org.netbeans.api.java.source.TreeMaker;
import org.netbeans.api.java.source.WorkingCopy;

/**
 *
 * @author bill
 */
class ConverterGenerator {

	WorkingCopy workingCopy;
	TreeMaker make;
	final ModifiersTree emptyModifiers;
	final TypeMirror arrayListType;
	final ModifiersTree finalModifiers;
	private int tempCounter = 0;


	static class ConverterFailException extends IllegalStateException {

		public ConverterFailException(String s) {
			super(s);
		}
	}

	public ConverterGenerator(WorkingCopy workingCopy, TreeMaker make) {
		this.workingCopy = workingCopy;
		this.make = make;
		finalModifiers = make.Modifiers(Collections.<Modifier>singleton(Modifier.FINAL), Collections.<AnnotationTree>emptyList());
		emptyModifiers = make.Modifiers(Collections.<Modifier>emptySet(), Collections.<AnnotationTree>emptyList());
		arrayListType = workingCopy.getElements().getTypeElement("java.util.ArrayList").asType();
	}

	StatementTree generateConverterAssignment(final TypeMirror inputType, ExpressionTree inputExpression, final TypeMirror outputType, final ExpressionTree outputLocation) {
		final List<StatementTree> supportingStatements = new ArrayList<StatementTree>();
		final ExpressionTree converter = generateConverter(supportingStatements, inputType, inputExpression, outputType, outputLocation);
		
		if (converter != null) {
			if (supportingStatements.isEmpty()) {
				return make.ExpressionStatement(make.Assignment(outputLocation, converter));
			}
			else {
				supportingStatements.add(make.ExpressionStatement(make.Assignment(outputLocation, converter)));
			}
		}
		if (!supportingStatements.isEmpty()) {
			return make.Block(supportingStatements, false);
		}
		else {
			throw new ConverterFailException("No converter expression or supporting body statements generated!");
		}
	}

	ExpressionTree generateConverterExpression(final List<StatementTree> statements, TypeMirror inputType, ExpressionTree expression, TypeMirror requiredType) {
		return generateConverterExpression(statements, inputType, expression, requiredType, makeTempName(requiredType));
	}
	
	ExpressionTree generateConverterExpression(final List<StatementTree> statements, TypeMirror inputType, ExpressionTree expression, TypeMirror requiredType, String outputKeyName) {
		final Types types = workingCopy.getTypes();
		if (!types.isAssignable(inputType, requiredType)) {
			List<StatementTree> converterStatements = new ArrayList<StatementTree>();
			final IdentifierTree outputKey = make.Identifier(outputKeyName);
			expression = generateConverter(converterStatements, inputType, expression, requiredType, outputKey);
			if (expression == null) {
				statements.add(make.Variable(finalModifiers, outputKeyName, make.Type(requiredType), null));
				expression = outputKey;
			}
			statements.addAll(converterStatements);
		}
		return expression;
	}

	private ExpressionTree generateConverter(List<StatementTree> body, final TypeMirror inputType, ExpressionTree inputExpression, final TypeMirror outputType, final ExpressionTree outputLocation) {
		System.out.println("generateConverter: " + inputType + " to " + outputType);
		final Types types = workingCopy.getTypes();
		if (types.isAssignable(inputType, outputType)) {
			return inputExpression;
		}

		if (outputType.getKind().isPrimitive() && inputType instanceof DeclaredType) {
			DeclaredType inputDeclaredType = (DeclaredType) inputType;
			TypeElement inputTypeElement = (TypeElement) inputDeclaredType.asElement();
			final ExecutableElement converterMethod = getMethod(inputTypeElement, outputType.toString() + "Value");
			if (converterMethod != null) {
				return make.MethodInvocation(Arrays.<ExpressionTree>asList(),
											  make.MemberSelect(inputExpression, converterMethod),
											  Arrays.<ExpressionTree>asList());
			}
			else if (getTypeAs(inputDeclaredType, "java.lang.Enum") != null) {
				return make.MethodInvocation(Arrays.<ExpressionTree>asList(),
											  make.MemberSelect(inputExpression, "ordinal"),
											  Arrays.<ExpressionTree>asList());
			}
		}
		
		if (inputType.getKind().isPrimitive() && outputType instanceof DeclaredType)
		{
			DeclaredType outputDeclaredType = (DeclaredType) outputType;
			TypeElement outputTypeElement = (TypeElement) outputDeclaredType.asElement();
			if (getTypeAs(outputDeclaredType, "java.lang.Enum") != null) {
				return make.ArrayAccess(make.MethodInvocation(Arrays.<ExpressionTree>asList(),
															  make.MemberSelect(make.Identifier(outputTypeElement), "values"),
															  Arrays.<ExpressionTree>asList()),
										generateTrivialConverter(inputType, inputExpression, types.getPrimitiveType(TypeKind.INT)));
			}
		}

		TypeMirror inputCollectionComponentType = getComponentType(inputType, null);
		TypeMirror outputCollectionComponentType = getComponentType(outputType, null);

		if (outputCollectionComponentType != null && inputCollectionComponentType != null) {
			generateArrayConverter(body, inputType, inputCollectionComponentType, inputExpression, outputType, outputCollectionComponentType, outputLocation);
			return null;
		}
		return generateTrivialConverter(inputType, inputExpression, outputType);
	}

	private void generateArrayConverter(List<StatementTree> body, final TypeMirror inputType, TypeMirror inputCollectionComponentType, ExpressionTree inputExpression, final TypeMirror outputType, TypeMirror outputCollectionComponentType, final ExpressionTree outputLocation) throws ConverterFailException {
		final Types types = workingCopy.getTypes();
		TypeMirror inputCollectionKeyType = getKeyType(inputType, types.getPrimitiveType(TypeKind.INT));
		TypeMirror outputCollectionKeyType = getKeyType(outputType, types.getPrimitiveType(TypeKind.INT));
		final String tempOutputName = makeTempName(outputType);
		List<StatementTree> copyBlock = new ArrayList<StatementTree>();
		final ExpressionTree outputContainerExpression;
		inputExpression = onceOnly(body, inputExpression, inputType);
		final boolean outputIsArray = outputType.getKind() == TypeKind.ARRAY;
		if (outputIsArray) {
			outputContainerExpression = make.NewArray(make.Type(stripTypeParameters(outputCollectionComponentType)),
													  Arrays.<ExpressionTree>asList(make.MemberSelect(inputExpression, "length")), null);
		}
		else if (outputType instanceof DeclaredType) {
			DeclaredType outputDeclaredType = (DeclaredType) outputType;
			TypeElement outputTypeElement = (TypeElement) outputDeclaredType.asElement();
			outputContainerExpression = make.NewClass(null, getTypeParametersTreeList(outputDeclaredType), make.Identifier(
					outputTypeElement), Arrays.<ExpressionTree>asList(), null);
		}
		else {
			throw new ConverterFailException("Don't know how to make an output type of this one");
		}
		final IdentifierTree tempOutput = make.Identifier(tempOutputName);
		copyBlock.add(make.Variable(emptyModifiers, tempOutputName, make.Type(outputType), outputContainerExpression));
		if (inputType.getKind() == TypeKind.ARRAY && outputIsArray && types.isAssignable(inputCollectionComponentType,
																						 outputCollectionComponentType)) {
			copyBlock.add(make.ExpressionStatement(
					make.MethodInvocation(Collections.<ExpressionTree>emptyList(),
										  make.MemberSelect(
					make.Identifier(workingCopy.getElements().getTypeElement("java.lang.System")),
					"arraycopy"),
										  Arrays.asList(inputExpression,
														make.Literal(0),
														tempOutput,
														make.Literal(0),
														make.MemberSelect(inputExpression, "length")))));
		}
		else {
			final ArrayList<StatementTree> elementCopyStatements = new ArrayList<StatementTree>();
			
			ExpressionTree keyExpression = null;
			ExpressionTree inputValueExpression;
			
			List<VariableTree> forLoopInitializer = null;
			BinaryTree forLoopCondition = null;
			List<ExpressionStatementTree> forLoopUpdate = null;
			VariableTree forLoopVariable = null;
			ExpressionTree forLoopExpression = null;
			
			DeclaredType inputMapType;
			
			if (inputType instanceof DeclaredType && (inputMapType = getTypeAs((DeclaredType) inputType, "java.util.Map")) != null) {
				final String entryName = makeTempName("entry");
				TypeMirror entry = null;
				for (Element element : workingCopy.getElements().getAllMembers((TypeElement) inputMapType.asElement())) {
					if (element.getSimpleName().contentEquals("Entry") && element instanceof TypeElement) {
						entry = types.getDeclaredType((TypeElement) element, inputCollectionKeyType, inputCollectionComponentType);
						break;
					}
				}
				forLoopVariable = make.Variable(emptyModifiers, entryName, make.Type(entry), null);
				forLoopExpression = make.MethodInvocation(Arrays.<ExpressionTree>asList(),
														  make.MemberSelect(inputExpression, "entrySet"),
														  Arrays.<ExpressionTree>asList());
				keyExpression = make.MethodInvocation(Arrays.<ExpressionTree>asList(),
													  make.MemberSelect(make.Identifier(entryName), "getKey"),
													  Arrays.<ExpressionTree>asList());
				inputValueExpression = make.MethodInvocation(Arrays.<ExpressionTree>asList(),
															 make.MemberSelect(make.Identifier(entryName), "getValue"),
															 Arrays.<ExpressionTree>asList());
			}
			else if (inputType instanceof DeclaredType && getTypeAs((DeclaredType) inputType, "java.util.List") != null) {
				final String entryName = makeTempName("entry");
				forLoopVariable = make.Variable(emptyModifiers, entryName, make.Type(inputCollectionComponentType), null);
				forLoopExpression = inputExpression;
				inputValueExpression = make.MethodInvocation(Arrays.<ExpressionTree>asList(), make.MemberSelect(make.Identifier(
						entryName), "getValue"), Arrays.<ExpressionTree>asList());
			}
			else {
				final String indexName = makeTempName("Index");
				final IdentifierTree indexIdentifier = make.Identifier(indexName);
				forLoopInitializer = Arrays.asList(make.Variable(emptyModifiers, indexName, make.PrimitiveType(TypeKind.INT),
																							make.Literal(0)));
				forLoopCondition = make.Binary(Tree.Kind.LESS_THAN, indexIdentifier, make.MemberSelect(inputExpression, "length"));
				forLoopUpdate = Arrays.asList(make.ExpressionStatement(make.Unary(Tree.Kind.POSTFIX_INCREMENT, indexIdentifier)));
				keyExpression = indexIdentifier;
				inputValueExpression = make.ArrayAccess(inputExpression, indexIdentifier);
			}
			final boolean outputIsMap = outputType instanceof DeclaredType && getTypeAs((DeclaredType) outputType, "java.util.Map") != null;
			if (keyExpression != null) {
				keyExpression = generateConverterExpression(elementCopyStatements,
															inputCollectionKeyType,
															keyExpression,
															outputCollectionKeyType,
															makeTempName("Key"));
			}
			else if (outputIsMap || outputIsArray) {
				final String indexName = makeTempName("Index");
				copyBlock.add(make.Variable(emptyModifiers, indexName, make.PrimitiveType(TypeKind.INT), make.Literal(0)));
				keyExpression = make.Unary(Tree.Kind.POSTFIX_INCREMENT, make.Identifier(indexName));
			}
			if (outputIsArray) {
				elementCopyStatements.add(generateConverterAssignment(inputCollectionComponentType,
																	  inputValueExpression,
																	  outputCollectionComponentType,
																	  make.ArrayAccess(tempOutput, keyExpression)));
			}
			else if (outputIsMap) {
				inputValueExpression = generateConverterExpression(elementCopyStatements,
																   inputCollectionComponentType,
																   inputValueExpression,
																   outputCollectionComponentType,
																   makeTempName("Value"));
				elementCopyStatements.add(make.ExpressionStatement(
						make.MethodInvocation(Arrays.<ExpressionTree>asList(),
											  make.MemberSelect(tempOutput, "put"),
											  Arrays.<ExpressionTree>asList(keyExpression,
																			inputValueExpression))));
			}
			else if (outputType instanceof DeclaredType && getTypeAs((DeclaredType) outputType, "java.util.List") != null) {
				inputValueExpression = generateConverterExpression(elementCopyStatements,
																   inputCollectionComponentType,
																   inputValueExpression,
																   outputCollectionComponentType,
																   makeTempName("Value"));
				elementCopyStatements.add(make.ExpressionStatement(
						make.MethodInvocation(Arrays.<ExpressionTree>asList(),
											  make.MemberSelect(tempOutput, "add"),
											  Arrays.<ExpressionTree>asList(inputValueExpression))));
			}
			else {
				throw new ConverterFailException("Don't know how to generate output for " + outputType);
			}
			
			
			if (forLoopInitializer != null) {
				copyBlock.add(make.ForLoop(forLoopInitializer, forLoopCondition, forLoopUpdate, make.Block(elementCopyStatements, true)));
			}
			else {
				copyBlock.add(make.EnhancedForLoop(forLoopVariable, forLoopExpression, make.Block(elementCopyStatements, true)));
			}
		}
		copyBlock.add(make.ExpressionStatement(make.Assignment(outputLocation, tempOutput)));
		ExpressionTree ifComparator = make.Binary(Tree.Kind.NOT_EQUAL_TO, inputExpression, make.Literal(null));
		ifComparator = make.Parenthesized(ifComparator); //Bug fix for netbeans?
		body.add(make.If(ifComparator,
						 make.Block(copyBlock, false),
						 make.ExpressionStatement(make.Assignment(outputLocation, make.Literal(null)))));
	}
	
	ExpressionTree generateTrivialConverter(final TypeMirror inputType, ExpressionTree inputExpression, final TypeMirror outputType) {
		if (!workingCopy.getTypes().isAssignable(inputType, outputType)) {
			inputExpression = make.TypeCast(make.Type(outputType), inputExpression);
		}
		return inputExpression;
	}

	ExecutableElement getMethod(TypeElement parent, CharSequence name) {
		final Elements elements = workingCopy.getElements();
		for (Element member : elements.getAllMembers(parent)) {
			if (member.getKind() == ElementKind.METHOD && member.getSimpleName().contentEquals(name)) {
				return (ExecutableElement) member;
			}
		}
		return null;
	}

	List<ExpressionTree> getTypeParametersTreeList(DeclaredType type) {
		ArrayList<ExpressionTree> typeParameters = new ArrayList<ExpressionTree>();
		final Types types = workingCopy.getTypes();
		for (TypeMirror parameter : type.getTypeArguments()) {
			final Element parameterElement = types.asElement(parameter);
			if (parameterElement != null) {
				typeParameters.add(make.Identifier(parameterElement));
			}
			else {
				System.out.println(
						"Couldn't make tree for parameterElement=" + parameterElement + " kind=" + parameterElement.getKind() + " class=" + parameterElement.getClass());
			}
		}
		return typeParameters;
	}

	void addBlockStatements(final BlockTree container, StatementTree extra) {
		if (extra instanceof BlockTree) {
			for (StatementTree element : ((BlockTree) extra).getStatements()) {
				make.addBlockStatement(container, element);
			}
		}
		else {
			make.addBlockStatement(container, extra);
		}
	}

	ExpressionTree onceOnly(List<StatementTree> statements, ExpressionTree input, final TypeMirror inputType) {
		if (!(input instanceof IdentifierTree)) {
			final String tempName = makeTempName(inputType);
			statements.add(make.Variable(finalModifiers, tempName, make.Type(inputType), input));
			input = make.Identifier(tempName);
		}
		return input;
	}

	TypeMirror getKeyType(final TypeMirror containerType, TypeMirror defaultComponentType) {
		System.out.println(
				"getComponentType of " + containerType + " kind=" + containerType.getKind() + " class=" + containerType.getClass());
		if (containerType.getKind() == TypeKind.ARRAY) {
			return workingCopy.getTypes().getPrimitiveType(TypeKind.INT);
		}
		else if (containerType instanceof DeclaredType) {
			TypeMirror keyType = getTypeArgumentOf(containerType, "java.util.Map", 0);
			if (keyType != null) {
				return keyType;
			}
		}
		return defaultComponentType;
	}

	DeclaredType getTypeAs(DeclaredType type, CharSequence name) {
		final Types types = workingCopy.getTypes();
		final Element element = types.asElement(type);
		if (element instanceof TypeElement) {
			final TypeElement typeElement = (TypeElement) element;
			if (typeElement.getQualifiedName().contentEquals(name)) {
				return type;
			}
		}
		for (TypeMirror supertype : types.directSupertypes(type)) {
			final DeclaredType t = getTypeAs((DeclaredType) supertype, name);
			if (t != null) {
				return t;
			}
		}
		return null;
	}

	TypeMirror getComponentType(final TypeMirror containerType, TypeMirror defaultComponentType) {
		System.out.println(
				"getComponentType of " + containerType + " kind=" + containerType.getKind() + " class=" + containerType.getClass());
		if (containerType.getKind() == TypeKind.ARRAY) {
			return ((ArrayType) containerType).getComponentType();
		}
		else if (containerType instanceof DeclaredType) {
			TypeMirror componentType = getTypeArgumentOf(containerType, "java.util.Map", 1);
			if (componentType != null) {
				return componentType;
			}
			componentType = getTypeArgumentOf(containerType, "java.util.List", 0);
			if (componentType != null) {
				return componentType;
			}
		}
		return defaultComponentType;
	}

	TypeMirror getTypeArgumentOf(final TypeMirror containerType, String baseTypeName, int position) {
		DeclaredType wantedType;
		if ((wantedType = getTypeAs((DeclaredType) containerType, baseTypeName)) != null) {
			final List<? extends TypeMirror> typeArguments = wantedType.getTypeArguments();
			if (typeArguments != null && !typeArguments.isEmpty()) {
				return typeArguments.get(position);
			}
		}
		return null;
	}

	private TypeMirror stripTypeParameters(TypeMirror type) {
		if (type instanceof DeclaredType) {
			TypeElement element = (TypeElement) ((DeclaredType) type).asElement();
			type = workingCopy.getTypes().getDeclaredType(element);
		}
		else if (type instanceof ArrayType) {
			final TypeMirror componentType = ((ArrayType) type).getComponentType();
			final TypeMirror strippedComponentType = stripTypeParameters(componentType);
			if (strippedComponentType != componentType) {
				type = workingCopy.getTypes().getArrayType(strippedComponentType);
			}
		}
		return type;
	}

	String makeTempName(TypeMirror inspiration) {
		String typeString = inspiration.toString();
		typeString = typeString.replaceAll("\\b([a-zA-Z0-9]+\\.)+", "");
		typeString = typeString.replaceAll("\\[\\]\\[\\]\\[\\]", "_Array3_");
		typeString = typeString.replaceAll("\\[\\]\\[\\]", "_Array2_");
		typeString = typeString.replaceAll("\\[\\]", "_Array_");
		return makeTempName(typeString);
	}

	String makeTempName(String inspiration) {
		return "gen" + (++tempCounter) + "_" + inspiration.toString().replaceAll("[^a-zA-Z0-9]+", "_").replaceFirst("_$", "");
	}
}

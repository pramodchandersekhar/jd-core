/*
 * Copyright (c) 2008, 2019 Emmanuel Dupuy.
 * This project is distributed under the GPLv3 license.
 * This is a Copyleft license that gives the user the right to use,
 * copy and modify the code freely for non-commercial purposes.
 */

package org.jd.core.v1.service.converter.classfiletojavasyntax.util;

import org.jd.core.v1.api.loader.Loader;
import org.jd.core.v1.api.loader.LoaderException;
import org.jd.core.v1.model.classfile.ClassFile;
import org.jd.core.v1.model.classfile.Field;
import org.jd.core.v1.model.classfile.Method;
import org.jd.core.v1.model.classfile.attribute.*;
import org.jd.core.v1.model.javasyntax.type.*;
import org.jd.core.v1.service.deserializer.classfile.ClassFileFormatException;
import org.jd.core.v1.service.deserializer.classfile.ClassFileReader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;

import static org.jd.core.v1.model.javasyntax.type.ObjectType.TYPE_UNDEFINED_OBJECT;

/*
 * https://jcp.org/aboutJava/communityprocess/maintenance/jsr924/JVMS-SE5.0-Ch4-ClassFile.pdf
 *
 * https://docs.oracle.com/javase/tutorial/extra/generics/methods.html
 *
 * http://www.angelikalanger.com/GenericsFAQ/JavaGenericsFAQ.html
 */
public class TypeMaker {
    private static final HashMap<String, ObjectType> INTERNALNAME_TO_OBJECTPRIMITIVETYPE = new HashMap<>();

    static {
        INTERNALNAME_TO_OBJECTPRIMITIVETYPE.put(ObjectType.TYPE_PRIMITIVE_BOOLEAN.getInternalName(), ObjectType.TYPE_PRIMITIVE_BOOLEAN);
        INTERNALNAME_TO_OBJECTPRIMITIVETYPE.put(ObjectType.TYPE_PRIMITIVE_BYTE.getInternalName(),    ObjectType.TYPE_PRIMITIVE_BYTE);
        INTERNALNAME_TO_OBJECTPRIMITIVETYPE.put(ObjectType.TYPE_PRIMITIVE_CHAR.getInternalName(),    ObjectType.TYPE_PRIMITIVE_CHAR);
        INTERNALNAME_TO_OBJECTPRIMITIVETYPE.put(ObjectType.TYPE_PRIMITIVE_DOUBLE.getInternalName(),  ObjectType.TYPE_PRIMITIVE_DOUBLE);
        INTERNALNAME_TO_OBJECTPRIMITIVETYPE.put(ObjectType.TYPE_PRIMITIVE_FLOAT.getInternalName(),   ObjectType.TYPE_PRIMITIVE_FLOAT);
        INTERNALNAME_TO_OBJECTPRIMITIVETYPE.put(ObjectType.TYPE_PRIMITIVE_INT.getInternalName(),     ObjectType.TYPE_PRIMITIVE_INT);
        INTERNALNAME_TO_OBJECTPRIMITIVETYPE.put(ObjectType.TYPE_PRIMITIVE_LONG.getInternalName(),    ObjectType.TYPE_PRIMITIVE_LONG);
        INTERNALNAME_TO_OBJECTPRIMITIVETYPE.put(ObjectType.TYPE_PRIMITIVE_SHORT.getInternalName(),   ObjectType.TYPE_PRIMITIVE_SHORT);
        INTERNALNAME_TO_OBJECTPRIMITIVETYPE.put(ObjectType.TYPE_PRIMITIVE_VOID.getInternalName(),    ObjectType.TYPE_PRIMITIVE_VOID);
    }

    private HashMap<String, Type> signatureToType = new HashMap<>(1024);
    private HashMap<String, Type> internalTypeNameFieldNameToType = new HashMap<>(1024);
    private HashMap<String, ObjectType> descriptorToObjectType = new HashMap<>(1024);
    private HashMap<String, ObjectType> internalTypeNameToObjectType = new HashMap<>(1024);
    private HashMap<String, MethodTypes> internalTypeNameMethodNameDescriptorToMethodTypes = new HashMap<>(1024);
    private HashMap<String, MethodTypes> signatureToMethodTypes = new HashMap<>(1024);
    private HashMap<String, BaseTypeParameter> internalTypeNameToTypeParameters = new HashMap<>(1024);

    private HashMap<String, String[]> hierarchy = new HashMap<>(1024);
    private ClassPathLoader classPathLoader = new ClassPathLoader();
    private Loader loader;

    public TypeMaker(Loader loader) {
        this.loader = loader;

        signatureToType.put("B", PrimitiveType.TYPE_BYTE);
        signatureToType.put("C", PrimitiveType.TYPE_CHAR);
        signatureToType.put("D", PrimitiveType.TYPE_DOUBLE);
        signatureToType.put("F", PrimitiveType.TYPE_FLOAT);
        signatureToType.put("I", PrimitiveType.TYPE_INT);
        signatureToType.put("J", PrimitiveType.TYPE_LONG);
        signatureToType.put("S", PrimitiveType.TYPE_SHORT);
        signatureToType.put("V", PrimitiveType.TYPE_VOID);
        signatureToType.put("Z", PrimitiveType.TYPE_BOOLEAN);
        signatureToType.put("Ljava/lang/Class;", ObjectType.TYPE_CLASS);
        signatureToType.put("Ljava/lang/Exception;", ObjectType.TYPE_EXCEPTION);
        signatureToType.put("Ljava/lang/Object;", ObjectType.TYPE_OBJECT);
        signatureToType.put("Ljava/lang/Throwable;", ObjectType.TYPE_THROWABLE);
        signatureToType.put("Ljava/lang/String;", ObjectType.TYPE_STRING);
        signatureToType.put("Ljava/lang/System;", ObjectType.TYPE_SYSTEM);

        descriptorToObjectType.put("Ljava/lang/Class;", ObjectType.TYPE_CLASS);
        descriptorToObjectType.put("Ljava/lang/Exception;", ObjectType.TYPE_EXCEPTION);
        descriptorToObjectType.put("Ljava/lang/Object;", ObjectType.TYPE_OBJECT);
        descriptorToObjectType.put("Ljava/lang/Throwable;", ObjectType.TYPE_THROWABLE);
        descriptorToObjectType.put("Ljava/lang/String;", ObjectType.TYPE_STRING);
        descriptorToObjectType.put("Ljava/lang/System;", ObjectType.TYPE_SYSTEM);

        internalTypeNameToObjectType.put("java/lang/Class", ObjectType.TYPE_CLASS);
        internalTypeNameToObjectType.put("java/lang/Exception", ObjectType.TYPE_EXCEPTION);
        internalTypeNameToObjectType.put("java/lang/Object", ObjectType.TYPE_OBJECT);
        internalTypeNameToObjectType.put("java/lang/Throwable", ObjectType.TYPE_THROWABLE);
        internalTypeNameToObjectType.put("java/lang/String", ObjectType.TYPE_STRING);
        internalTypeNameToObjectType.put("java/lang/System", ObjectType.TYPE_SYSTEM);
    }

    /**
     * Rules:
     *  ClassSignature: TypeParameters? SuperclassSignature SuperInterfaceSignature*
     *  SuperclassSignature: ClassTypeSignature
     *  SuperInterfaceSignature: ClassTypeSignature
     */
    @SuppressWarnings("unchecked")
    public TypeTypes parseClassFileSignature(ClassFile classFile) {
        TypeTypes typeTypes = new TypeTypes();
        String internalTypeName = classFile.getInternalTypeName();

        typeTypes.thisType = makeFromInternalTypeName(internalTypeName);

        AttributeSignature attributeSignature = classFile.getAttribute("Signature");

        if (attributeSignature == null) {
            // Create 'typeSignature' with classFile start
            String superTypeName = classFile.getSuperTypeName();
            String[] interfaceTypeNames = classFile.getInterfaceTypeNames();

            if (! "java/lang/Object".equals(superTypeName)) {
                typeTypes.superType = makeFromInternalTypeName(superTypeName);
            }

            if (interfaceTypeNames != null) {
                int length = interfaceTypeNames.length;

                if (length == 1) {
                    typeTypes.interfaces = makeFromInternalTypeName(interfaceTypeNames[0]);
                } else {
                    Types list = new Types(length);
                    for (String interfaceTypeName : interfaceTypeNames) {
                        list.add(makeFromInternalTypeName(interfaceTypeName));
                    }
                    typeTypes.interfaces = list;
                }
            }
        } else {
            // Parse 'signature' attribute
            SignatureReader reader = new SignatureReader(attributeSignature.getSignature());

            typeTypes.typeParameters = parseTypeParameters(reader);
            typeTypes.superType = parseClassTypeSignature(reader, 0);

            Type firstInterface = parseClassTypeSignature(reader, 0);

            if (firstInterface != null) {
                Type nextInterface = parseClassTypeSignature(reader, 0);

                if (nextInterface == null) {
                    typeTypes.interfaces = firstInterface;
                } else {
                    Types list = new Types(classFile.getInterfaceTypeNames().length);

                    list.add(firstInterface);

                    do {
                        list.add(nextInterface);
                        nextInterface = parseClassTypeSignature(reader, 0);
                    } while (nextInterface != null);

                    typeTypes.interfaces = list;
                }
            }
        }

        return typeTypes;
    }

    public MethodTypes parseConstructorSignature(ClassFile classFile, Method method) {
        String key = classFile.getInternalTypeName() + ":<init>" + method.getDescriptor();
        return parseConstructorOrMethodSignature(method, key);
    }

    public MethodTypes parseMethodSignature(ClassFile classFile, Method method) {
        String key = classFile.getInternalTypeName() + ':' + method.getName() + method.getDescriptor();
        return parseConstructorOrMethodSignature(method, key);
    }

    private MethodTypes parseConstructorOrMethodSignature(Method method, String key) {
        AttributeSignature attributeSignature = method.getAttribute("Signature");
        String[] exceptionTypeNames = getExceptionTypeNames(method);
        MethodTypes methodTypes;

        if (attributeSignature == null) {
            methodTypes = parseMethodSignature(method.getDescriptor(), exceptionTypeNames);
        } else {
            methodTypes = parseMethodSignature(method.getDescriptor(), attributeSignature.getSignature(), exceptionTypeNames);
        }

        internalTypeNameMethodNameDescriptorToMethodTypes.put(key, methodTypes);

        return methodTypes;
    }

    private static String[] getExceptionTypeNames(Method method) {
        if (method != null) {
            AttributeExceptions attributeExceptions = method.getAttribute("Exceptions");

            if (attributeExceptions != null) {
                return attributeExceptions.getExceptionTypeNames();
            }
        }

        return null;
    }

    public Type parseFieldSignature(ClassFile classFile, Field field) {
        String key = classFile.getInternalTypeName() + ':' + field.getName();
        AttributeSignature attributeSignature = field.getAttribute("Signature");
        String signature = (attributeSignature == null) ? field.getDescriptor() : attributeSignature.getSignature();
        Type type = makeFromSignature(signature);

        internalTypeNameFieldNameToType.put(key, type);

        return type;
    }

    public Type makeFromSignature(String signature) {
        Type type = signatureToType.get(signature);

        if (type == null) {
            SignatureReader reader = new SignatureReader(signature);
            type = parseReferenceTypeSignature(reader);
            signatureToType.put(signature, type);
        }

        return type;
    }

    public static int countDimension(String descriptor) {
        int count = 0;

        for (int i=0, len=descriptor.length(); (i<len) && (descriptor.charAt(i)=='['); i++) {
            count++;
        }

        return count;
    }

    private MethodTypes parseMethodSignature(String descriptor, String signature, String[] exceptionTypeNames) {
        if (signature == null) {
            return parseMethodSignature(descriptor, exceptionTypeNames);
        } else {
            // Signature does not contain synthetic parameterTypes like outer type name, for example.
            MethodTypes mtDescriptor = parseMethodSignature(descriptor, exceptionTypeNames);
            MethodTypes mtSignature  = parseMethodSignature(signature, exceptionTypeNames);

            if (mtDescriptor.parameterTypes == null) {
                return mtSignature;
            } else if (mtSignature.parameterTypes == null) {
                MethodTypes mt = new MethodTypes();

                mt.typeParameters = mtSignature.typeParameters;
                mt.parameterTypes = mtDescriptor.parameterTypes;
                mt.returnedType = mtSignature.returnedType;
                mt.exceptions = mtSignature.exceptions;

                return mt;
            } else if (mtDescriptor.parameterTypes.size() == mtSignature.parameterTypes.size()) {
                return mtSignature;
            } else {
                Types<Type> parameterTypes = new Types<>(mtDescriptor.parameterTypes.getList());
                parameterTypes.subList(parameterTypes.size() - mtSignature.parameterTypes.size(), parameterTypes.size()).clear();
                parameterTypes.addAll(mtSignature.parameterTypes.getList());

                MethodTypes mt = new MethodTypes();

                mt.typeParameters = mtSignature.typeParameters;
                mt.parameterTypes = parameterTypes;
                mt.returnedType = mtSignature.returnedType;
                mt.exceptions = mtSignature.exceptions;

                return mt;
            }
        }
    }

    /**
     * Rules:
     *  MethodTypeSignature: TypeParameters? '(' ReferenceTypeSignature* ')' ReturnType ThrowsSignature*
     *  ReturnType: TypeSignature | VoidDescriptor
     *  ThrowsSignature: '^' ClassTypeSignature | '^' TypeVariableSignature
     */
    @SuppressWarnings("unchecked")
    private MethodTypes parseMethodSignature(String signature, String[] exceptionTypeNames) {
        String cacheKey = signature;
        boolean containsThrowsSignature = (signature.indexOf('^') != -1);

        if (!containsThrowsSignature && (exceptionTypeNames != null)) {
            StringBuilder sb = new StringBuilder(signature);

            for (String exceptionTypeName : exceptionTypeNames) {
                sb.append("^L").append(exceptionTypeName).append(';');
            }

            cacheKey = sb.toString();
        }

        MethodTypes methodTypes = signatureToMethodTypes.get(cacheKey);

        if (methodTypes == null) {
            SignatureReader reader = new SignatureReader(signature);

            // Type parameterTypes
            methodTypes = new MethodTypes();
            methodTypes.typeParameters = parseTypeParameters(reader);

            // Parameters
            if (reader.read() != '(') {
                throw new SignatureFormatException(signature);
            }

            Type firstParameterType = parseReferenceTypeSignature(reader);

            if (firstParameterType == null) {
                methodTypes.parameterTypes = null;
            } else {
                Type nextParameterType = parseReferenceTypeSignature(reader);
                Types types = new Types();

                types.add(firstParameterType);

                while (nextParameterType != null) {
                    types.add(nextParameterType);
                    nextParameterType = parseReferenceTypeSignature(reader);
                }

                methodTypes.parameterTypes = types;
            }

            if (reader.read() != ')') {
                throw new SignatureFormatException(signature);
            }

            // Result
            methodTypes.returnedType = parseReferenceTypeSignature(reader);

            // Exceptions
            Type firstException = parseExceptionSignature(reader);

            if (firstException == null) {
                // Signature does not contain exceptions
                if (exceptionTypeNames != null) {
                    if (exceptionTypeNames.length == 1) {
                        methodTypes.exceptions = makeFromInternalTypeName(exceptionTypeNames[0]);
                    } else {
                        Types list = new Types(exceptionTypeNames.length);

                        for (String exceptionTypeName : exceptionTypeNames) {
                            list.add(makeFromInternalTypeName(exceptionTypeName));
                        }

                        methodTypes.exceptions = list;
                    }
                }
            } else {
                Type nextException = parseExceptionSignature(reader);

                if (nextException == null) {
                    methodTypes.exceptions = firstException;
                } else {
                    Types list = new Types();

                    list.add(firstException);

                    do {
                        list.add(nextException);
                        nextException = parseExceptionSignature(reader);
                    } while (nextException != null);

                    methodTypes.exceptions = list;
                }
            }

            signatureToMethodTypes.put(cacheKey, methodTypes);
        }

        return methodTypes;
    }

    /**
     * Rules:
     *  TypeParameters: '<' TypeParameter+ '>'
     */
    @SuppressWarnings("unchecked")
    private BaseTypeParameter parseTypeParameters(SignatureReader reader) {
        if (reader.nextEqualsTo('<')) {
            // Skip '<'
            reader.index++;

            TypeParameter firstTypeParameter = parseTypeParameter(reader);

            if (firstTypeParameter == null) {
                throw new SignatureFormatException(reader.signature);
            }

            TypeParameter nextTypeParameter = parseTypeParameter(reader);
            BaseTypeParameter typeParameters;

            if (nextTypeParameter == null) {
                typeParameters = firstTypeParameter;
            } else {
                TypeParameters list = new TypeParameters();
                list.add(firstTypeParameter);

                do {
                    list.add(nextTypeParameter);
                    nextTypeParameter = parseTypeParameter(reader);
                } while (nextTypeParameter != null);

                typeParameters = list;
            }

            if (reader.read() != '>') {
                throw new SignatureFormatException(reader.signature);
            }

            return typeParameters;
        } else {
            return null;
        }
    }

    /**
     * Rules:
     *  TypeParameter: Identifier ClassBound InterfaceBound*
     *  ClassBound: ':' FieldTypeSignature?
     *  InterfaceBound: ':' FieldTypeSignature
     */
    @SuppressWarnings("unchecked")
    private TypeParameter parseTypeParameter(SignatureReader reader) {
        int fistIndex = reader.index;

        // Search ':'
        if (reader.search(':')) {
            String identifier = reader.substring(fistIndex);

            // Parser bounds
            Type firstBound = null;
            TypeBounds types = null;

            while (reader.nextEqualsTo(':')) {
                // Skip ':'
                reader.index++;

                Type bound = parseReferenceTypeSignature(reader);

                if ((bound != null) && !bound.getDescriptor().equals("Ljava/lang/Object;")) {
                    if (firstBound == null) {
                        firstBound = bound;
                    } else if (types == null) {
                        types = new TypeBounds();
                        types.add(firstBound);
                        types.add(bound);
                    } else {
                        types.add(bound);
                    }
                }
            }

            if (firstBound == null) {
                return new TypeParameter(identifier);
            } else if (types == null) {
                return new TypeParameterWithTypeBounds(identifier, firstBound);
            } else {
                return new TypeParameterWithTypeBounds(identifier, types);
            }
        } else {
            return null;
        }
    }

    /**
     * Rules:
     *  ThrowsSignature: '^' ClassTypeSignature | '^' TypeVariableSignature
     */
    private Type parseExceptionSignature(SignatureReader reader) {
        if (reader.nextEqualsTo('^')) {
            // Skip '^'
            reader.index++;

            return parseReferenceTypeSignature(reader);
        } else {
            return null;
        }
    }

    /**
     * Rules:
     *  ClassTypeSignature: 'L' PackageSpecifier* SimpleClassTypeSignature ClassTypeSignatureSuffix* ';'
     *  SimpleClassTypeSignature: Identifier TypeArguments?
     *  ClassTypeSignatureSuffix: '.' SimpleClassTypeSignature
     */
    private ObjectType parseClassTypeSignature(SignatureReader reader, int dimension) {
        if (reader.nextEqualsTo('L')) {
            // Skip 'L'. Parse 'PackageSpecifier* SimpleClassTypeSignature'
            int index = ++reader.index;
            char endMarker = reader.searchEndMarker();

            if (endMarker == 0) {
                throw new SignatureFormatException(reader.signature);
            }

            String internalTypeName = reader.substring(index);
            ObjectType ot = makeFromInternalTypeName(internalTypeName);

            if (endMarker == '<') {
                // Skip '<'
                reader.index++;
                ot = ot.createType(parseTypeArguments(reader));
                if (reader.read() != '>')
                    throw new SignatureFormatException(reader.signature);
            }

            // Parse 'ClassTypeSignatureSuffix*'
            while (reader.nextEqualsTo('.')) {
                // Skip '.'
                index = ++reader.index;
                endMarker = reader.searchEndMarker();

                if (endMarker == 0) {
                    throw new SignatureFormatException(reader.signature);
                }

                String name = reader.substring(index);
                internalTypeName += '$' + name;
                String qualifiedName;

                if (Character.isDigit(name.charAt(0))) {
                    name = extractLocalClassName(name);
                    qualifiedName = null;
                } else {
                    qualifiedName = ot.getQualifiedName() + '.' + name;
                }

                if (endMarker == '<') {
                    // Skip '<'
                    reader.index++;

                    BaseTypeArgument typeArguments = parseTypeArguments(reader);
                    if (reader.read() != '>') {
                        throw new SignatureFormatException(reader.signature);
                    }

                    ot = new InnerObjectType(internalTypeName, qualifiedName, name, typeArguments, ot);
                } else {
                    ot = new InnerObjectType(internalTypeName, qualifiedName, name, ot);
                }
            }

            // Skip ';'
            reader.index++;

            return (dimension==0) ? ot : (ObjectType)ot.createType(dimension);
        } else {
            return null;
        }
    }

    /**
     * Rules:
     *  TypeArguments: '<' TypeArgument+ '>'
     */
    private BaseTypeArgument parseTypeArguments(SignatureReader reader) {
        TypeArgument firstTypeArgument = parseTypeArgument(reader);

        if (firstTypeArgument == null) {
            throw new SignatureFormatException(reader.signature);
        }

        TypeArgument nextTypeArgument = parseTypeArgument(reader);

        if (nextTypeArgument == null) {
            return firstTypeArgument;
        } else {
            ArrayTypeArguments typeArguments = new ArrayTypeArguments();
            typeArguments.add(firstTypeArgument);

            do {
                typeArguments.add(nextTypeArgument);
                nextTypeArgument = parseTypeArgument(reader);
            } while (nextTypeArgument != null);

            return typeArguments;
        }
    }

    /**
     * Rules:
     *  ReferenceTypeSignature: ClassTypeSignature | ArrayTypeSignature | TypeVariableSignature
     *  SimpleClassTypeSignature: Identifier TypeArguments?
     *  ArrayTypeSignature: '[' TypeSignature
     *  TypeSignature: '[' FieldTypeSignature | '[' BaseType
     *  BaseType: 'B' | 'C' | 'D' | 'F' | 'I' | 'J' | 'S' | 'Z'
     *  TypeVariableSignature: 'T' Identifier ';'
     */
    private Type parseReferenceTypeSignature(SignatureReader reader) {
        if (reader.available()) {
            int dimension = 0;
            char c = reader.read();

            while (c == '[') {
                dimension++;
                c = reader.read();
            }

            switch (c) {
                case 'B':
                    return (dimension == 0) ? PrimitiveType.TYPE_BYTE : PrimitiveType.TYPE_BYTE.createType(dimension);
                case 'C':
                    return (dimension == 0) ? PrimitiveType.TYPE_CHAR : PrimitiveType.TYPE_CHAR.createType(dimension);
                case 'D':
                    return (dimension == 0) ? PrimitiveType.TYPE_DOUBLE : PrimitiveType.TYPE_DOUBLE.createType(dimension);
                case 'F':
                    return (dimension == 0) ? PrimitiveType.TYPE_FLOAT : PrimitiveType.TYPE_FLOAT.createType(dimension);
                case 'I':
                    return (dimension == 0) ? PrimitiveType.TYPE_INT : PrimitiveType.TYPE_INT.createType(dimension);
                case 'J':
                    return (dimension == 0) ? PrimitiveType.TYPE_LONG : PrimitiveType.TYPE_LONG.createType(dimension);
                case 'L':
                    // Unread 'L'
                    reader.index--;
                    return parseClassTypeSignature(reader, dimension);
                case 'S':
                    return (dimension == 0) ? PrimitiveType.TYPE_SHORT : PrimitiveType.TYPE_SHORT.createType(dimension);
                case 'T':
                    int index = reader.index;

                    if (reader.search(';') == false)
                        return null;

                    String identifier = reader.substring(index);

                    // Skip ';'
                    reader.index++;

                    return new GenericType(identifier, dimension);
                case 'V':
                    assert dimension == 0;
                    return PrimitiveType.TYPE_VOID;
                case 'Z':
                    return (dimension == 0) ? PrimitiveType.TYPE_BOOLEAN : PrimitiveType.TYPE_BOOLEAN.createType(dimension);
                default:
                    // Unread 'c'
                    reader.index--;
                    return null;
            }
        } else {
            return null;
        }
    }

    /**
     * Rules:
     *  TypeArgument: WildcardIndicator? FieldTypeSignature | '*'
     *  WildcardIndicator: '+' | '-'
     */
    private TypeArgument parseTypeArgument(SignatureReader reader) {
        switch (reader.read()) {
            case '+':
                return new WildcardExtendsTypeArgument(parseReferenceTypeSignature(reader));
            case '-':
                return new WildcardSuperTypeArgument(parseReferenceTypeSignature(reader));
            case '*':
                return WildcardTypeArgument.WILDCARD_TYPE_ARGUMENT;
            default:
                // Unread 'c'
                reader.index--;
                return parseReferenceTypeSignature(reader);
        }
    }

    private static boolean isAReferenceTypeSignature(SignatureReader reader) {
        if (reader.available()) {
            // Skip dimension
            char c = reader.read();

            while (c == '[') {
                c = reader.read();
            }

            switch (c) {
                case 'B': case 'C': case 'D': case 'F': case 'I': case 'J':
                    return true;
                case 'L':
                    // Unread 'L'
                    reader.index--;
                    return isAClassTypeSignature(reader);
                case 'S':
                    return true;
                case 'T':
                    reader.searchEndMarker();
                    return true;
                case 'V': case 'Z':
                    return true;
                default:
                    // Unread 'c'
                    reader.index--;
                    return false;
            }
        } else {
            return false;
        }
    }

    private static boolean isAClassTypeSignature(SignatureReader reader) {
        if (reader.nextEqualsTo('L')) {
            // Skip 'L'
            reader.index++;

            // Parse 'PackageSpecifier* SimpleClassTypeSignature'
            char endMarker = reader.searchEndMarker();

            if (endMarker == 0)
                throw new SignatureFormatException(reader.signature);

            if (endMarker == '<') {
                // Skip '<'
                reader.index++;
                isATypeArguments(reader);
                if (reader.read() != '>')
                    throw new SignatureFormatException(reader.signature);
            }

            // Parse 'ClassTypeSignatureSuffix*'
            while (reader.nextEqualsTo('.')) {
                // Skip '.'
                reader.index++;

                endMarker = reader.searchEndMarker();

                if (endMarker == 0)
                    throw new SignatureFormatException(reader.signature);

                if (endMarker == '<') {
                    // Skip '<'
                    reader.index++;
                    isATypeArguments(reader);
                    if (reader.read() != '>')
                        throw new SignatureFormatException(reader.signature);
                }
            }

            // Skip ';'
            reader.index++;

            return true;
        } else {
            return false;
        }
    }

    private static boolean isATypeArguments(SignatureReader reader) {
        if (isATypeArgument(reader) == false)
            throw new SignatureFormatException(reader.signature);

        while (isATypeArgument(reader));

        return true;
    }

    private static boolean isATypeArgument(SignatureReader reader) {
        switch (reader.read()) {
            case '+': case '-':
                return isAReferenceTypeSignature(reader);
            case '*':
                return true;
            default:
                // Unread 'c'
                reader.index--;
                return isAReferenceTypeSignature(reader);
        }
    }

    private static String extractLocalClassName(String name) {
        if (Character.isDigit(name.charAt(0))) {
            int i = 0, len = name.length();

            while ((i < len) && Character.isDigit(name.charAt(i))) {
                i++;
            }

            return (i == len) ? null : name.substring(i);
        }

        return name;
    }

    public ObjectType makeFromDescriptorOrInternalTypeName(String descriptorOrInternalTypeName) {
        return (descriptorOrInternalTypeName.charAt(0) == '[') ? makeFromDescriptor(descriptorOrInternalTypeName) : makeFromInternalTypeName(descriptorOrInternalTypeName);
    }

    public ObjectType makeFromDescriptor(String descriptor) {
        ObjectType ot = descriptorToObjectType.get(descriptor);

        if (ot == null) {
            if (descriptor.charAt(0) == '[') {
                int dimension = 1;

                while (descriptor.charAt(dimension) == '[') {
                    dimension++;
                }

                ot = (ObjectType)makeFromDescriptorWithoutBracket(descriptor.substring(dimension)).createType(dimension);
            } else {
                ot = makeFromDescriptorWithoutBracket(descriptor);
            }

            descriptorToObjectType.put(descriptor, ot);
        }

        return ot;
    }

    private ObjectType makeFromDescriptorWithoutBracket(String descriptor) {
        ObjectType ot = INTERNALNAME_TO_OBJECTPRIMITIVETYPE.get(descriptor);

        if (ot == null) {
            ot = makeFromInternalTypeName(descriptor.substring(1, descriptor.length()-1));
        }

        return ot;
    }

    public ObjectType makeFromInternalTypeName(String internalTypeName) {
        assert (internalTypeName != null) && !internalTypeName.endsWith(";") : "ObjectTypeMaker.makeFromInternalTypeName(internalTypeName) : invalid internalTypeName";

        ObjectType ot = loadType(internalTypeName);

        if (ot == null) {
            // File not found with the system class loader -> Create type from 'internalTypeName'
            ot = create(internalTypeName);
        }

        return ot;
    }

    private ObjectType create(String internalTypeName) {
        int lastSlash = internalTypeName.lastIndexOf('/');
        int lastDollar = internalTypeName.lastIndexOf('$');
        ObjectType ot;

        if (lastSlash < lastDollar) {
            String outerTypeName = internalTypeName.substring(0, lastDollar);
            ObjectType outerSot = create(outerTypeName);
            String innerName = internalTypeName.substring(outerTypeName.length() + 1);

            if (innerName.isEmpty()) {
                String qualifiedName = internalTypeName.replace('/', '.');
                String name = qualifiedName.substring(lastSlash + 1);
                ot = new ObjectType(internalTypeName, qualifiedName, name);
            } else if (Character.isDigit(innerName.charAt(0))) {
                ot = new InnerObjectType(internalTypeName, null, extractLocalClassName(innerName), outerSot);
            } else {
                String qualifiedName = outerSot.getQualifiedName() + '.' + innerName;
                ot = new InnerObjectType(internalTypeName, qualifiedName, innerName, outerSot);
            }
        } else {
            String qualifiedName = internalTypeName.replace('/', '.');
            String name = qualifiedName.substring(lastSlash + 1);
            ot = new ObjectType(internalTypeName, qualifiedName, name);
        }

        internalTypeNameToObjectType.put(internalTypeName, ot);

        return ot;
    }

    public boolean isAssignable(ObjectType parent, ObjectType child) {
        if (parent == TYPE_UNDEFINED_OBJECT) {
            return true;
        } else if (parent.getDimension() > 0) {
            return (parent.getDimension() == child.getDimension()) && parent.getInternalName().equals(child.getInternalName());
        } else {
            String parentInternalName = parent.getInternalName();
            String childInternalName = child.getInternalName();

            if (parentInternalName.equals(childInternalName) || parentInternalName.equals("java/lang/Object")) {
                return true;
            }

            return recursiveIsAssignable(parentInternalName, childInternalName);
        }
    }

    @SuppressWarnings("unchecked")
    private boolean recursiveIsAssignable(String parentInternalName, String childInternalName) {
        if (childInternalName.equals("java/lang/Object"))
            return false;

        String[] superClassAndInterfaceNames = hierarchy.get(childInternalName);

        if (superClassAndInterfaceNames == null) {
            loadType(childInternalName);
            superClassAndInterfaceNames = hierarchy.get(childInternalName);
        }

        if (superClassAndInterfaceNames != null) {
            for (String name : superClassAndInterfaceNames) {
                if (parentInternalName.equals(name))
                    return true;
            }

            for (String name : superClassAndInterfaceNames) {
                if (recursiveIsAssignable(parentInternalName, name))
                    return true;
            }
        }

        return false;
    }

    public BaseTypeParameter makeTypeParameters(ObjectType objectType) {
        String internalTypeName = objectType.getInternalName();

        if (internalTypeNameToTypeParameters.containsKey(internalTypeName)) {
            return internalTypeNameToTypeParameters.get(internalTypeName);
        }

        BaseTypeParameter typeParameters = null;

        try {
            if (loader.canLoad(internalTypeName)) {
                internalTypeNameToTypeParameters.put(internalTypeName, typeParameters = loadTypeParameters(internalTypeName, loader.load(internalTypeName)));
            } else if (classPathLoader.canLoad(internalTypeName)) {
                internalTypeNameToTypeParameters.put(internalTypeName, typeParameters = loadTypeParameters(internalTypeName, classPathLoader.load(internalTypeName)));
            }
        } catch (Exception e) {
            assert ExceptionUtil.printStackTrace(e);
        }

        return typeParameters;
    }

    private BaseTypeParameter loadTypeParameters(String internalTypeName, byte[] data) throws Exception {
        if (data == null) {
            return null;
        }

        ClassFileReader reader = new ClassFileReader(data);
        Object[] constants = loadClassFile(internalTypeName, reader);

        // Skip fields
        skipMembers(reader);

        // Skip methods
        skipMembers(reader);

        String outerTypeName = null;
        ObjectType outerObjectType = null;

        // Load attributes
        String signature = null;
        int count = reader.readUnsignedShort();

        for (int j=0; j<count; j++) {
            int attributeNameIndex = reader.readUnsignedShort();
            int attributeLength = reader.readInt();

            if ("Signature".equals(constants[attributeNameIndex])) {
                signature = (String)constants[reader.readUnsignedShort()];
                break;
            } else {
                reader.skip(attributeLength);
            }
        }

        if (signature == null) {
            return null;
        }

        SignatureReader signatureReader = new SignatureReader(signature);
        return parseTypeParameters(signatureReader);
    }

    public Type makeFieldType(ObjectType objectType, String fieldName, String descriptor) {
        String internalTypeName = objectType.getInternalName();
        String key = internalTypeName + ':' + fieldName;
        Type type = internalTypeNameFieldNameToType.get(key);

        if (type == null) {
            // Load fields
            if (loadFieldsAndMethods(internalTypeName)) {
                type = internalTypeNameFieldNameToType.get(key);
                if (type == null) {
                    internalTypeNameFieldNameToType.put(key, type = makeFromSignature(descriptor));
                }
            } else {
                internalTypeNameFieldNameToType.put(key, type = makeFromSignature(descriptor));
            }
        }

        return type;
    }

    public MethodTypes makeMethodTypes(String descriptor) {
        return parseMethodSignature(descriptor, null);
    }

    public MethodTypes makeMethodTypes(ObjectType objectType, String methodName, String descriptor) {
        String internalTypeName = objectType.getInternalName();
        String key = internalTypeName + ':' + methodName + descriptor;
        MethodTypes methodTypes = internalTypeNameMethodNameDescriptorToMethodTypes.get(key);

        if (methodTypes == null) {
            // Load method
            if (loadFieldsAndMethods(internalTypeName)) {
                methodTypes = internalTypeNameMethodNameDescriptorToMethodTypes.get(key);
                if (methodTypes == null) {
                    internalTypeNameMethodNameDescriptorToMethodTypes.put(key, methodTypes = parseMethodSignature(descriptor, null));
                }
            } else {
                internalTypeNameMethodNameDescriptorToMethodTypes.put(key, methodTypes = parseMethodSignature(descriptor, null));
            }
        }

        return methodTypes;
    }

    private ObjectType loadType(String internalTypeName) {
        ObjectType ot = internalTypeNameToObjectType.get(internalTypeName);

        if (ot == null) {
            try {
                if (loader.canLoad(internalTypeName)) {
                    internalTypeNameToObjectType.put(internalTypeName, ot = loadType(internalTypeName, loader.load(internalTypeName)));
                } else if (classPathLoader.canLoad(internalTypeName)) {
                    internalTypeNameToObjectType.put(internalTypeName, ot = loadType(internalTypeName, classPathLoader.load(internalTypeName)));
                }
            } catch (Exception e) {
                assert ExceptionUtil.printStackTrace(e);
            }
        }

        return ot;
    }

    private ObjectType loadType(String internalTypeName, byte[] data) throws Exception {
        if (data == null) {
            return null;
        }

        ClassFileReader reader = new ClassFileReader(data);
        Object[] constants = loadClassFile(internalTypeName, reader);

        // Skip fields
        skipMembers(reader);

        // Skip methods
        skipMembers(reader);

        String outerTypeName = null;
        ObjectType outerObjectType = null;

        // Load attributes
        int count = reader.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            int attributeNameIndex = reader.readUnsignedShort();
            int attributeLength = reader.readInt();

            if ("InnerClasses".equals(constants[attributeNameIndex])) {
                int innerClassesCount = reader.readUnsignedShort();

                for(int j=0; j < innerClassesCount; j++) {
                    int innerTypeIndex = reader.readUnsignedShort();
                    int outerTypeIndex = reader.readUnsignedShort();

                    // Skip 'innerNameIndex' & innerAccessFlags'
                    reader.skip(2 * 2);

                    Integer cc = (Integer)constants[innerTypeIndex];
                    String innerTypeName = (String)constants[cc.intValue()];

                    if (innerTypeName.equals(internalTypeName)) {
                        if (outerTypeIndex == 0) {
                            // Synthetic inner class -> Search outer class
                            int lastDollar = internalTypeName.lastIndexOf('$');

                            if (lastDollar != -1) {
                                outerTypeName = internalTypeName.substring(0, lastDollar);
                                outerObjectType = loadType(outerTypeName);
                            }
                        } else {
                            // Return 'outerTypeName'
                            cc = (Integer)constants[outerTypeIndex];
                            outerTypeName = (String)constants[cc.intValue()];
                            outerObjectType = loadType(outerTypeName);
                        }
                        break;
                    }
                }

                break;
            } else {
                reader.skip(attributeLength);
            }
        }

        if (outerObjectType == null) {
            int lastSlash = internalTypeName.lastIndexOf('/');
            String qualifiedName = internalTypeName.replace('/', '.');
            String name = qualifiedName.substring(lastSlash + 1);

            return new ObjectType(internalTypeName, qualifiedName, name);
        } else {
            int index;

            if (internalTypeName.length() > outerTypeName.length() + 1) {
                index = outerTypeName.length();
            } else {
                index = internalTypeName.lastIndexOf('$');
            }

            String innerName = internalTypeName.substring(index + 1);

            if (Character.isDigit(innerName.charAt(0))) {
                return new InnerObjectType(internalTypeName, null, extractLocalClassName(innerName), outerObjectType);
            } else {
                String qualifiedName = outerObjectType.getQualifiedName() + '.' + innerName;
                return new InnerObjectType(internalTypeName, qualifiedName, innerName, outerObjectType);
            }
        }
    }

    private boolean loadFieldsAndMethods(String internalTypeName) {
        try {
            if (loader.canLoad(internalTypeName)) {
                loadFieldsAndMethods(internalTypeName, loader.load(internalTypeName));
                return true;
            } else if (classPathLoader.canLoad(internalTypeName)) {
                loadFieldsAndMethods(internalTypeName, classPathLoader.load(internalTypeName));
                return true;
            }
        } catch (Exception e) {
            assert ExceptionUtil.printStackTrace(e);
        }

        return false;
    }

    private void loadFieldsAndMethods(String internalTypeName, byte[] data) throws Exception {
        if (data != null) {
            ClassFileReader reader = new ClassFileReader(data);
            Object[] constants = loadClassFile(internalTypeName, reader);

            // Load fields
            int count = reader.readUnsignedShort();
            for (int i = 0; i < count; i++) {
                // skip 'accessFlags'
                reader.skip(2);

                int nameIndex = reader.readUnsignedShort();
                int descriptorIndex = reader.readUnsignedShort();

                // Load attributes
                String signature = null;
                int attributeCount = reader.readUnsignedShort();

                for (int j=0; j<attributeCount; j++) {
                    int attributeNameIndex = reader.readUnsignedShort();
                    int attributeLength = reader.readInt();

                    if ("Signature".equals(constants[attributeNameIndex])) {
                        signature = (String)constants[reader.readUnsignedShort()];
                    } else {
                        reader.skip(attributeLength);
                    }
                }

                String name = (String)constants[nameIndex];
                String descriptor = (String)constants[descriptorIndex];
                String key = internalTypeName + ':' + name;

                if (signature == null) {
                    internalTypeNameFieldNameToType.put(key, makeFromSignature(descriptor));
                } else {
                    internalTypeNameFieldNameToType.put(key, makeFromSignature(signature));
                }
            }

            // Load methods
            count = reader.readUnsignedShort();
            for (int i = 0; i < count; i++) {
                // skip 'accessFlags'
                reader.skip(2);

                int nameIndex = reader.readUnsignedShort();
                int descriptorIndex = reader.readUnsignedShort();

                // Load attributes
                String signature = null;
                String[] exceptionTypeNames = null;
                int attributeCount = reader.readUnsignedShort();

                for (int j=0; j<attributeCount; j++) {
                    int attributeNameIndex = reader.readUnsignedShort();
                    int attributeLength = reader.readInt();
                    String name = (String)constants[attributeNameIndex];

                    switch (name) {
                        case "Signature":
                            signature = (String)constants[reader.readUnsignedShort()];
                            break;
                        case "Exceptions":
                            int exceptionCount = reader.readUnsignedShort();
                            if (exceptionCount > 0) {
                                exceptionTypeNames = new String[exceptionCount];

                                for (int k=0; k<exceptionCount; k++) {
                                    int exceptionClassIndex = reader.readUnsignedShort();
                                    Integer cc = (Integer)constants[exceptionClassIndex];
                                    exceptionTypeNames[k] = (String)constants[cc.intValue()];
                                }
                            }
                            break;
                        default:
                            reader.skip(attributeLength);
                            break;
                    }
                }

                String name = (String)constants[nameIndex];
                String descriptor = (String)constants[descriptorIndex];
                String key = internalTypeName + ':' + name + descriptor;

                if (signature == null) {
                    internalTypeNameMethodNameDescriptorToMethodTypes.put(key, parseMethodSignature(descriptor, exceptionTypeNames));
                } else {
                    internalTypeNameMethodNameDescriptorToMethodTypes.put(key, parseMethodSignature(descriptor, signature, exceptionTypeNames));
                }
            }
        }
    }

    private Object[] loadClassFile(String internalTypeName, ClassFileReader reader) throws Exception {
        int magic = reader.readInt();

        if (magic != ClassFileReader.JAVA_MAGIC_NUMBER) {
            throw new ClassFileFormatException("Invalid CLASS file");
        }

        // Skip 'minorVersion', 'majorVersion'
        reader.skip(2 * 2);

        Object[] constants = loadConstants(reader);

        // Skip 'accessFlags' & 'thisClassIndex'
        reader.skip(2 * 2);

        // Load super class name
        int superClassIndex = reader.readUnsignedShort();
        String superClassName;

        if (superClassIndex == 0) {
            superClassName = null;
        } else {
            Integer cc = (Integer)constants[superClassIndex];
            superClassName = (String)constants[cc.intValue()];
        }

        // Load interface names
        int count = reader.readUnsignedShort();
        String[] superClassAndInterfaceNames = new String[count + 1];

        superClassAndInterfaceNames[0] = superClassName;

        for (int i = 1; i <= count; i++) {
            int interfaceIndex = reader.readUnsignedShort();
            Integer cc = (Integer)constants[interfaceIndex];
            superClassAndInterfaceNames[i] = (String)constants[cc.intValue()];
        }

        hierarchy.put(internalTypeName, superClassAndInterfaceNames);

        return constants;
    }

    private static void skipMembers(ClassFileReader reader) {
        int count = reader.readUnsignedShort();
        for (int i = 0; i < count; i++) {
            // skip 'accessFlags', 'nameIndex', 'descriptorIndex'
            reader.skip(3 * 2);
            skipAttributes(reader);
        }
    }

    private Object[] loadConstants(ClassFileReader reader) throws Exception {
        int count = reader.readUnsignedShort();

        if (count == 0)
            return null;

        Object[] constants = new Object[count];

        for (int i=1; i<count; i++) {
            int tag = reader.readByte();

            switch (tag) {
                case 1:
                    constants[i] = reader.readUTF8();
                    break;
                case 7:
                    constants[i] = Integer.valueOf(reader.readUnsignedShort());
                    break;
                case 8: case 16: case 19: case 20:
                    reader.skip(2);
                    break;
                case 15:
                    reader.skip(3);
                    break;
                case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18:
                    reader.skip(4);
                    break;
                case 5: case 6:
                    reader.skip(8);
                    i++;
                    break;
                default:
                    throw new ClassFileFormatException("Invalid constant pool entry");
            }
        }

        return constants;
    }

    private static void skipAttributes(ClassFileReader reader) {
        int count = reader.readUnsignedShort();

        for (int i = 0; i < count; i++) {
            // skip 'attributeNameIndex'
            reader.skip(2);

            int attributeLength = reader.readInt();

            reader.skip(attributeLength);
        }
    }

    private static class ClassPathLoader implements Loader {
        protected byte[] buffer = new byte[1024*5];

        @Override
        public byte[] load(String internalName) throws LoaderException {
            InputStream is = this.getClass().getResourceAsStream("/" + internalName + ".class");

            if (is == null) {
                return null;
            } else {
                try (InputStream in=is; ByteArrayOutputStream out=new ByteArrayOutputStream()) {
                    int read = in.read(buffer);

                    while (read > 0) {
                        out.write(buffer, 0, read);
                        read = in.read(buffer);
                    }

                    return out.toByteArray();
                } catch (IOException e) {
                    throw new LoaderException(e);
                }
            }
        }

        @Override
        public boolean canLoad(String internalName) {
            return this.getClass().getResource("/" + internalName + ".class") != null;
        }
    }

    private static class SignatureReader {
        public String signature;
        public char[] array;
        public int length;
        public int index = 0;

        public SignatureReader(String signature) {
            this(signature, 0);
        }

        public SignatureReader(String signature, int index) {
            this.signature = signature;
            this.array = signature.toCharArray();
            this.length = array.length;
            this.index = index;
        }

        public char read() {
            return array[index++];
        }

        public boolean nextEqualsTo(char c) {
            return (index < length) && (array[index] == c);
        }

        public boolean search(char c) {
            int length = array.length;

            for (int i=index; i<length; i++) {
                if (array[i] == c) {
                    index = i;
                    return true;
                }
            }

            return false;
        }

        public char searchEndMarker() {
            int length = array.length;

            while (index < length) {
                char c = array[index];

                if ((c == ';') || (c == '<') || (c == '.')) {
                    return c;
                }

                index++;
            }

            return 0;
        }

        public boolean available() {
            return index < length;
        }

        public String substring(int beginIndex) {
            return new String(array, beginIndex, index-beginIndex);
        }

        @Override
        public String toString() {
            return "SignatureReader{index=" + index + ", nextChars=" + (new String(array, index, length-index)) + "}";
        }
    }

    public static class TypeTypes {
        public ObjectType thisType;
        public BaseTypeParameter typeParameters;
        public ObjectType superType;
        public BaseType interfaces;
    }

    public static class MethodTypes {
        public BaseTypeParameter typeParameters;
        public BaseType parameterTypes;
        public Type returnedType;
        public BaseType exceptions;
    }
}
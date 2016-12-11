package scripts

import org.apache.commons.lang.StringUtils
import org.reflections.Reflections
import org.zstack.core.Platform
import org.zstack.header.exception.CloudRuntimeException
import org.zstack.header.rest.APINoSee
import org.zstack.header.rest.RestResponse
import org.zstack.rest.sdk.JavaSdkTemplate
import org.zstack.rest.sdk.SdkFile
import org.zstack.utils.FieldUtils

import java.lang.reflect.Field

/**
 * Created by xing5 on 2016/12/11.
 */
class SdkDataStructureGenerator implements JavaSdkTemplate {
    Set<Class> responseClasses
    Map<Class, SdkFile> sdkFileMap = [:]
    Set<Class> laterResolvedClasses = []

    Reflections reflections = Platform.reflections

    SdkDataStructureGenerator() {
        Reflections reflections = Platform.getReflections()
        responseClasses = reflections.getTypesAnnotatedWith(RestResponse.class)
    }

    @Override
    List<SdkFile> generate() {
        responseClasses.each { c -> generateResponseClass(c) }
        resolveAllClasses()
        return sdkFileMap.values() as List
    }

    def resolveAllClasses() {
        if (laterResolvedClasses.isEmpty()) {
            return
        }

        Set<Class> toResolve = []
        toResolve.addAll(laterResolvedClasses)

        toResolve.each { Class clz ->
            resolveClass(clz)
            laterResolvedClasses.remove(clz)
        }
    }

    def resolveClass(Class clz) {
        if (sdkFileMap.containsKey(clz)) {
            return
        }

        if (!Object.class.isAssignableFrom(clz.superclass)) {
            addToLaterResolvedClassesIfNeed(clz.superclass)
        }

        def output = []
        for (Field f : clz.getDeclaredFields()) {
            if (f.isAnnotationPresent(APINoSee.class)) {
                continue
            }

            output.add(makeFieldText(f.name, f))
        }

        SdkFile file = new SdkFile()
        file.fileName = "${clz.simpleName}.java"
        file.content = """package org.zstack.sdk;

public class ${clz.simpleName} ${Object.class.isAssignableFrom(clz.superclass) ? "" : clz.superclass.simpleName} {
${output.join("\n")}
}
"""
        sdkFileMap.put(clz, file)
    }

    def isZStackClass(Class clz) {
        if (clz.name.startsWith("java.")) {
            return false
        } else if (clz.name.startsWith("org.zstack")) {
            return true
        } else {
            throw new CloudRuntimeException("${clz.name} is neither JRE class nor ZStack class")
        }
    }

    def addToLaterResolvedClassesIfNeed(Class clz) {
        if (!sdkFileMap.containsKey(clz)) {
            laterResolvedClasses.add(clz)
        }
    }

    def generateResponseClass(Class responseClass) {
        RestResponse at = responseClass.getAnnotation(RestResponse.class)

        def fields = [:]

        def addToFields = { String fname, Field f ->
            if (isZStackClass(f.type)) {
                def fs = f.type.getDeclaredFields()
                fs.each { ff ->
                    if (!ff.isAnnotationPresent(APINoSee.class)) {
                        fields[f.name] = ff
                    }
                }

                if (!Object.class.isAssignableFrom(f.type.superclass)) {
                    addToLaterResolvedClassesIfNeed(f.type.superclass)
                }

            } else {
                fields[fname] = f
            }
        }

        if (!at.mappingAllTo().isEmpty()) {
            Field f = responseClass.getField(at.mappingAllTo())
            addToFields(at.mappingAllTo(), f)
        } else {
            at.mappingFields().each { s ->
                def ss = s.split("=")
                def dst = ss[0].trim()
                def src = ss[1].trim()

                Field f = responseClass.getField(src)
                addToFields(dst, f)
            }
        }
        
        def output = []
        fields.each { String name, Field f ->
            output.add(makeFieldText(name, f))
        }

        def className = responseClass.simpleName
        className = StringUtils.stripStart(className, "API")
        className = StringUtils.stripEnd(className, "Event")
        className = StringUtils.stripEnd(className, "Reply")
        className = StringUtils.capitalize(className)
        className = "${className}Result"

        SdkFile file = new SdkFile()
        file.fileName = "${className}.java"
        file.content = """package org.zstack.sdk;

public class ${className} {
${output.join("\n")}
}
"""
        sdkFileMap[responseClass] = file
    }

    def makeFieldText(String fname, Field field) {
        // zstack type
        if (isZStackClass(field.type)) {
            addToLaterResolvedClassesIfNeed(field.type)

            return """\
    public ${field.type.simpleName} ${fname};
"""
        }

        // java type
        if (Collection.class.isAssignableFrom(field.type)) {
            Class genericType = FieldUtils.getGenericType(field)

            if (genericType != null) {
                if (isZStackClass(genericType)) {
                    addToLaterResolvedClassesIfNeed(genericType)
                }

                return """\
    public ${field.type.simpleName}<${genericType.simpleName}> ${fname};
"""
            } else {
                return """\
    public ${field.type.simpleName} ${fname};
"""
            }
        } else if (Map.class.isAssignableFrom(field.type)) {
            Class genericType = FieldUtils.getGenericType(field)

            if (genericType != null) {
                if (isZStackClass(genericType)) {
                    addToLaterResolvedClassesIfNeed(genericType)
                }

                return """\
    public ${field.type.simpleName}<String, ${genericType.simpleName}> ${fname};
"""
            } else {
                return """\
    public ${field.type.simpleName} ${fname};
"""
            }
        } else {
            return """\
    public ${field.type.simpleName} ${fname};
"""
        }
    }
}
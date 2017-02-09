/*
 * Copyright 2008 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.axellience.vuegwt.client.gwtextension;

import com.axellience.vuegwt.jsr69.TemplateGenerator;
import com.axellience.vuegwt.jsr69.annotations.Computed;
import com.axellience.vuegwt.template.TemplateParser;
import com.axellience.vuegwt.template.TemplateParserResult;
import com.axellience.vuegwt.template.VariableInfo;
import com.google.gwt.core.ext.Generator;
import com.google.gwt.core.ext.TreeLogger;
import com.google.gwt.core.ext.UnableToCompleteException;
import com.google.gwt.core.ext.typeinfo.JClassType;
import com.google.gwt.core.ext.typeinfo.JMethod;
import com.google.gwt.core.ext.typeinfo.TypeOracle;
import com.google.gwt.dev.util.Util;
import com.google.gwt.resources.client.ClientBundle.Source;
import com.google.gwt.resources.ext.AbstractResourceGenerator;
import com.google.gwt.resources.ext.ResourceContext;
import com.google.gwt.resources.ext.ResourceGeneratorUtil;
import com.google.gwt.resources.ext.SupportsGeneratorResultCaching;
import com.google.gwt.user.rebind.SourceWriter;
import com.google.gwt.user.rebind.StringSourceWriter;

import java.net.URL;
import java.util.Map.Entry;

/**
 * Source: GWT Project http://www.gwtproject.org/
 * <p>
 * Modified by Adrien Baron
 * Modification: Doesn't throw an exception if the resource doesn't exist, just return an empty
 * String
 */
public final class TemplateResourceGenerator extends AbstractResourceGenerator
    implements SupportsGeneratorResultCaching
{
    /**
     * Java compiler has a limit of 2^16 bytes for encoding string constants in a
     * class file. Since the max size of a character is 4 bytes, we'll limit the
     * number of characters to (2^14 - 1) to fit within one record.
     */
    private static final int MAX_STRING_CHUNK = 16383;

    @Override
    public String createAssignment(TreeLogger logger, ResourceContext context, JMethod method)
    throws UnableToCompleteException
    {
        URL[] resources;
        try
        {
            resources = ResourceGeneratorUtil.findResources(logger, context, method);
        }
        catch (UnableToCompleteException e)
        {
            resources = null;
        }

        URL resource = resources == null ? null : resources[0];

        SourceWriter sw = new StringSourceWriter();

        // No resource for the template
        if (resource == null)
        {
            sw.println("new " + TemplateResource.class.getName() + "() {");
            sw.indent();
            sw.println("public String getText() {return \"\";}");
            sw.println("public String getName() {return \"" + method.getName() + "\";}");
            sw.outdent();
            sw.println("}");
            return sw.toString();
        }

        if (!AbstractResourceGenerator.STRIP_COMMENTS)
        {
            // Convenience when examining the generated code.
            sw.println("// " + resource.toExternalForm());
        }

        TypeOracle typeOracle = context.getGeneratorContext().getTypeOracle();
        Source resourceAnnotation = method.getAnnotation(Source.class);
        String resourcePath = resourceAnnotation.value()[0];
        String typeName = resourcePath.substring(0, resourcePath.length() - 5).replaceAll("/", ".");

        // Start class
        sw.println("new " + typeName + TemplateGenerator.TEMPLATE_RESOURCE_SUFFIX + "() {");
        sw.indent();

        // Get template content from HTML file
        String templateContent = Util.readURLAsString(resource);

        TemplateParser templateParser = new TemplateParser();
        TemplateParserResult templateParserResult =
            templateParser.parseHtmlTemplate(templateContent, typeOracle.findType(typeName));
        templateContent = templateParserResult.getTemplateWithReplacements();

        for (VariableInfo variableInfo : templateParserResult.getLocalVariables())
        {
            sw.println("@jsinterop.annotations.JsProperty");
            sw.println("public " + variableInfo.getType().getQualifiedSourceName() + " " +
                variableInfo.getJavaName() + ";");
        }

        // Add computed properties
        JClassType jClassType = typeOracle.findType(typeName);
        for (JMethod jMethod : jClassType.getMethods())
        {
            Computed computed = jMethod.getAnnotation(Computed.class);
            if (computed == null)
                continue;

            String propertyName = "$" + jMethod.getName();
            if (!"".equals(computed.propertyName()))
                propertyName = computed.propertyName();

            sw.println("@jsinterop.annotations.JsProperty");
            sw.println(jMethod.getReturnType().getQualifiedSourceName() + " " + propertyName + ";");
        }

        sw.println("public String getText() {");
        sw.indent();

        if (templateContent.length() > MAX_STRING_CHUNK)
        {
            writeLongString(sw, templateContent);
        }
        else
        {
            sw.println("return \"" + Generator.escape(templateContent) + "\";");
        }
        sw.outdent();
        sw.println("}");

        sw.println("public String getName() {");
        sw.indent();
        sw.println("return \"" + method.getName() + "\";");
        sw.outdent();
        sw.println("}");

        for (Entry<String, String> entry : templateParserResult.getTemplateExpressions().entrySet())
        {
            String expression = entry.getValue().trim();
            boolean isMethodCall = false;
            if (")".equals(expression.substring(expression.length() - 1)))
            {
                isMethodCall = true;
            }

            sw.println("@jsinterop.annotations.JsMethod");
            if (isMethodCall)
            {
                sw.println("public void " + entry.getKey() + "() {");
                sw.indent();
                sw.println(entry.getValue() + ";");
                sw.outdent();
                sw.println("}");
            }
            else
            {
                sw.println("public Object " + entry.getKey() + "() {");
                sw.indent();
                sw.println("return " + entry.getValue() + ";");
                sw.outdent();
                sw.println("}");
            }
        }

        for (Entry<String, String> entry : templateParserResult.getCollectionsExpressions()
            .entrySet())
        {
            sw.println("@jsinterop.annotations.JsMethod");
            sw.println("public Object " + entry.getKey() + "() {");
            sw.indent();
            sw.println("return " + entry.getValue() + ";");
            sw.outdent();
            sw.println("}");
        }

        sw.outdent();
        sw.println("}");

        return sw.toString();
    }

    /**
     * A single constant that is too long will crash the compiler with an out of
     * memory error. Break up the constant and generate code that appends using a
     * buffer.
     */
    private void writeLongString(SourceWriter sw, String toWrite)
    {
        sw.println("StringBuilder builder = new StringBuilder();");
        int offset = 0;
        int length = toWrite.length();
        while (offset < length - 1)
        {
            int subLength = Math.min(MAX_STRING_CHUNK, length - offset);
            sw.print("builder.append(\"");
            sw.print(Generator.escape(toWrite.substring(offset, offset + subLength)));
            sw.println("\");");
            offset += subLength;
        }
        sw.println("return builder.toString();");
    }
}
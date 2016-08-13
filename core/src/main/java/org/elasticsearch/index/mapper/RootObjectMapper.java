/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.index.mapper;

import org.elasticsearch.Version;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.joda.FormatDateTimeFormatter;
import org.elasticsearch.common.joda.Joda;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.index.mapper.DynamicTemplate.XContentFieldType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.elasticsearch.common.xcontent.support.XContentMapValues.lenientNodeBooleanValue;
import static org.elasticsearch.index.mapper.TypeParsers.parseDateTimeFormatter;

/**
 *
 */
public class RootObjectMapper extends ObjectMapper {

    public static class Defaults {
        public static final FormatDateTimeFormatter[] DYNAMIC_DATE_TIME_FORMATTERS =
                new FormatDateTimeFormatter[]{
                        DateFieldMapper.DEFAULT_DATE_TIME_FORMATTER,
                        Joda.getStrictStandardDateFormatter()
                };
        public static final boolean DATE_DETECTION = true;
        public static final boolean NUMERIC_DETECTION = false;
    }

    public static class Builder extends ObjectMapper.Builder<Builder, RootObjectMapper> {

        protected final List<DynamicTemplate> dynamicTemplates = new ArrayList<>();

        // we use this to filter out seen date formats, because we might get duplicates during merging
        protected Set<String> seenDateFormats = new HashSet<>();
        protected List<FormatDateTimeFormatter> dynamicDateTimeFormatters = new ArrayList<>();

        protected boolean dateDetection = Defaults.DATE_DETECTION;
        protected boolean numericDetection = Defaults.NUMERIC_DETECTION;

        public Builder(String name) {
            super(name);
            this.builder = this;
        }

        public Builder noDynamicDateTimeFormatter() {
            this.dynamicDateTimeFormatters = null;
            return builder;
        }

        public Builder dynamicDateTimeFormatter(Iterable<FormatDateTimeFormatter> dateTimeFormatters) {
            for (FormatDateTimeFormatter dateTimeFormatter : dateTimeFormatters) {
                if (!seenDateFormats.contains(dateTimeFormatter.format())) {
                    seenDateFormats.add(dateTimeFormatter.format());
                    this.dynamicDateTimeFormatters.add(dateTimeFormatter);
                }
            }
            return builder;
        }

        public Builder add(DynamicTemplate dynamicTemplate) {
            this.dynamicTemplates.add(dynamicTemplate);
            return this;
        }

        public Builder add(DynamicTemplate... dynamicTemplate) {
            for (DynamicTemplate template : dynamicTemplate) {
                this.dynamicTemplates.add(template);
            }
            return this;
        }


        @Override
        protected ObjectMapper createMapper(String name, String fullPath, boolean enabled, Nested nested, Dynamic dynamic, Map<String, Mapper> mappers, @Nullable Settings settings) {
            assert !nested.isNested();
            FormatDateTimeFormatter[] dates = null;
            if (dynamicDateTimeFormatters == null) {
                dates = new FormatDateTimeFormatter[0];
            } else if (dynamicDateTimeFormatters.isEmpty()) {
                // add the default one
                dates = Defaults.DYNAMIC_DATE_TIME_FORMATTERS;
            } else {
                dates = dynamicDateTimeFormatters.toArray(new FormatDateTimeFormatter[dynamicDateTimeFormatters.size()]);
            }
            return new RootObjectMapper(name, enabled, dynamic, mappers,
                    dates,
                    dynamicTemplates.toArray(new DynamicTemplate[dynamicTemplates.size()]),
                    dateDetection, numericDetection);
        }
    }

    public static class TypeParser extends ObjectMapper.TypeParser {

        @Override
        protected ObjectMapper.Builder createBuilder(String name) {
            return new Builder(name);
        }

        @Override
        public Mapper.Builder parse(String name, Map<String, Object> node, ParserContext parserContext) throws MapperParsingException {

            ObjectMapper.Builder builder = createBuilder(name);
            Iterator<Map.Entry<String, Object>> iterator = node.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Object> entry = iterator.next();
                String fieldName = entry.getKey();
                Object fieldNode = entry.getValue();
                if (parseObjectOrDocumentTypeProperties(fieldName, fieldNode, parserContext, builder)
                        || processField(builder, fieldName, fieldNode, parserContext.indexVersionCreated())) {
                    iterator.remove();
                }
            }
            return builder;
        }

        protected boolean processField(ObjectMapper.Builder builder, String fieldName, Object fieldNode,
                Version indexVersionCreated) {
            if (fieldName.equals("date_formats") || fieldName.equals("dynamic_date_formats")) {
                List<FormatDateTimeFormatter> dateTimeFormatters = new ArrayList<>();
                if (fieldNode instanceof List) {
                    for (Object node1 : (List) fieldNode) {
                        if (node1.toString().startsWith("epoch_")) {
                            throw new MapperParsingException("Epoch ["+ node1.toString() +"] is not supported as dynamic date format");
                        }
                        dateTimeFormatters.add(parseDateTimeFormatter(node1));
                    }
                } else if ("none".equals(fieldNode.toString())) {
                    dateTimeFormatters = null;
                } else {
                    dateTimeFormatters.add(parseDateTimeFormatter(fieldNode));
                }
                if (dateTimeFormatters == null) {
                    ((Builder) builder).noDynamicDateTimeFormatter();
                } else {
                    ((Builder) builder).dynamicDateTimeFormatter(dateTimeFormatters);
                }
                return true;
            } else if (fieldName.equals("dynamic_templates")) {
                //  "dynamic_templates" : [
                //      {
                //          "template_1" : {
                //              "match" : "*_test",
                //              "match_mapping_type" : "string",
                //              "mapping" : { "type" : "string", "store" : "yes" }
                //          }
                //      }
                //  ]
                List tmplNodes = (List) fieldNode;
                for (Object tmplNode : tmplNodes) {
                    Map<String, Object> tmpl = (Map<String, Object>) tmplNode;
                    if (tmpl.size() != 1) {
                        throw new MapperParsingException("A dynamic template must be defined with a name");
                    }
                    Map.Entry<String, Object> entry = tmpl.entrySet().iterator().next();
                    String templateName = entry.getKey();
                    Map<String, Object> templateParams = (Map<String, Object>) entry.getValue();
                    DynamicTemplate template = DynamicTemplate.parse(templateName, templateParams, indexVersionCreated);
                    if (template != null) {
                        ((Builder) builder).add(template);
                    }
                }
                return true;
            } else if (fieldName.equals("date_detection")) {
                ((Builder) builder).dateDetection = lenientNodeBooleanValue(fieldNode);
                return true;
            } else if (fieldName.equals("numeric_detection")) {
                ((Builder) builder).numericDetection = lenientNodeBooleanValue(fieldNode);
                return true;
            }
            return false;
        }
    }

    private final FormatDateTimeFormatter[] dynamicDateTimeFormatters;

    private final boolean dateDetection;
    private final boolean numericDetection;

    private volatile DynamicTemplate dynamicTemplates[];

    RootObjectMapper(String name, boolean enabled, Dynamic dynamic, Map<String, Mapper> mappers,
                     FormatDateTimeFormatter[] dynamicDateTimeFormatters, DynamicTemplate dynamicTemplates[], boolean dateDetection, boolean numericDetection) {
        super(name, name, enabled, Nested.NO, dynamic, mappers);
        this.dynamicTemplates = dynamicTemplates;
        this.dynamicDateTimeFormatters = dynamicDateTimeFormatters;
        this.dateDetection = dateDetection;
        this.numericDetection = numericDetection;
    }

    @Override
    public ObjectMapper mappingUpdate(Mapper mapper) {
        RootObjectMapper update = (RootObjectMapper) super.mappingUpdate(mapper);
        // dynamic templates are irrelevant for dynamic mappings updates
        update.dynamicTemplates = new DynamicTemplate[0];
        return update;
    }

    public boolean dateDetection() {
        return this.dateDetection;
    }

    public boolean numericDetection() {
        return this.numericDetection;
    }

    public FormatDateTimeFormatter[] dynamicDateTimeFormatters() {
        return dynamicDateTimeFormatters;
    }

    public Mapper.Builder findTemplateBuilder(ParseContext context, String name, XContentFieldType matchType) {
        return findTemplateBuilder(context, name, matchType.defaultMappingType(), matchType);
    }

    /**
     * Find a template. Returns {@code null} if no template could be found.
     * @param name        the field name
     * @param dynamicType the field type to give the field if the template does not define one
     * @param matchType   the type of the field in the json document or null if unknown
     * @return a mapper builder, or null if there is no template for such a field
     */
    public Mapper.Builder findTemplateBuilder(ParseContext context, String name, String dynamicType, XContentFieldType matchType) {
        DynamicTemplate dynamicTemplate = findTemplate(context.path(), name, matchType);
        if (dynamicTemplate == null) {
            return null;
        }
        Mapper.TypeParser.ParserContext parserContext = context.docMapperParser().parserContext(name);
        String mappingType = dynamicTemplate.mappingType(dynamicType);
        Mapper.TypeParser typeParser = parserContext.typeParser(mappingType);
        if (typeParser == null) {
            throw new MapperParsingException("failed to find type parsed [" + mappingType + "] for [" + name + "]");
        }
        return typeParser.parse(name, dynamicTemplate.mappingForName(name, dynamicType), parserContext);
    }

    public DynamicTemplate findTemplate(ContentPath path, String name, XContentFieldType matchType) {
        final String pathAsString = path.pathAsText(name);
        for (DynamicTemplate dynamicTemplate : dynamicTemplates) {
            if (dynamicTemplate.match(pathAsString, name, matchType)) {
                return dynamicTemplate;
            }
        }
        return null;
    }

    @Override
    public RootObjectMapper merge(Mapper mergeWith, boolean updateAllTypes) {
        return (RootObjectMapper) super.merge(mergeWith, updateAllTypes);
    }

    @Override
    protected void doMerge(ObjectMapper mergeWith, boolean updateAllTypes) {
        super.doMerge(mergeWith, updateAllTypes);
        RootObjectMapper mergeWithObject = (RootObjectMapper) mergeWith;
        // merge them
        List<DynamicTemplate> mergedTemplates = new ArrayList<>(Arrays.asList(this.dynamicTemplates));
        for (DynamicTemplate template : mergeWithObject.dynamicTemplates) {
            boolean replaced = false;
            for (int i = 0; i < mergedTemplates.size(); i++) {
                if (mergedTemplates.get(i).name().equals(template.name())) {
                    mergedTemplates.set(i, template);
                    replaced = true;
                }
            }
            if (!replaced) {
                mergedTemplates.add(template);
            }
        }
        this.dynamicTemplates = mergedTemplates.toArray(new DynamicTemplate[mergedTemplates.size()]);
    }

    @Override
    public RootObjectMapper updateFieldType(Map<String, MappedFieldType> fullNameToFieldType) {
        return (RootObjectMapper) super.updateFieldType(fullNameToFieldType);
    }

    @Override
    protected void doXContent(XContentBuilder builder, ToXContent.Params params) throws IOException {
        if (dynamicDateTimeFormatters != Defaults.DYNAMIC_DATE_TIME_FORMATTERS) {
            if (dynamicDateTimeFormatters.length > 0) {
                builder.startArray("dynamic_date_formats");
                for (FormatDateTimeFormatter dateTimeFormatter : dynamicDateTimeFormatters) {
                    builder.value(dateTimeFormatter.format());
                }
                builder.endArray();
            }
        }

        if (dynamicTemplates != null && dynamicTemplates.length > 0) {
            builder.startArray("dynamic_templates");
            for (DynamicTemplate dynamicTemplate : dynamicTemplates) {
                builder.startObject();
                builder.field(dynamicTemplate.name(), dynamicTemplate);
                builder.endObject();
            }
            builder.endArray();
        }

        if (dateDetection != Defaults.DATE_DETECTION) {
            builder.field("date_detection", dateDetection);
        }
        if (numericDetection != Defaults.NUMERIC_DETECTION) {
            builder.field("numeric_detection", numericDetection);
        }
    }
}

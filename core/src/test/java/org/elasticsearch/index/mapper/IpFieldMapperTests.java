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

import org.apache.lucene.document.InetAddressPoint;
import org.apache.lucene.index.DocValuesType;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.compress.CompressedXContent;
import org.elasticsearch.common.network.InetAddresses;
import org.elasticsearch.common.xcontent.ToXContent;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentFactory;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.mapper.DocumentMapper;
import org.elasticsearch.index.mapper.DocumentMapperParser;
import org.elasticsearch.index.mapper.IpFieldMapper;
import org.elasticsearch.index.mapper.MapperParsingException;
import org.elasticsearch.index.mapper.ParsedDocument;
import org.elasticsearch.test.ESSingleNodeTestCase;
import org.junit.Before;

import static org.hamcrest.Matchers.containsString;

import java.io.IOException;
import java.net.InetAddress;

public class IpFieldMapperTests extends ESSingleNodeTestCase {

    IndexService indexService;
    DocumentMapperParser parser;

    @Before
    public void before() {
        indexService = createIndex("test");
        parser = indexService.mapperService().documentMapperParser();
    }

    public void testDefaults() throws Exception {
        String mapping = XContentFactory.jsonBuilder().startObject().startObject("type")
                .startObject("properties").startObject("field").field("type", "ip").endObject().endObject()
                .endObject().endObject().string();

        DocumentMapper mapper = parser.parse("type", new CompressedXContent(mapping));

        assertEquals(mapping, mapper.mappingSource().toString());

        ParsedDocument doc = mapper.parse("test", "type", "1", XContentFactory.jsonBuilder()
                .startObject()
                .field("field", "::1")
                .endObject()
                .bytes());

        IndexableField[] fields = doc.rootDoc().getFields("field");
        assertEquals(2, fields.length);
        IndexableField pointField = fields[0];
        assertEquals(1, pointField.fieldType().pointDimensionCount());
        assertEquals(16, pointField.fieldType().pointNumBytes());
        assertFalse(pointField.fieldType().stored());
        assertEquals(new BytesRef(InetAddressPoint.encode(InetAddresses.forString("::1"))), pointField.binaryValue());
        IndexableField dvField = fields[1];
        assertEquals(DocValuesType.SORTED_SET, dvField.fieldType().docValuesType());
        assertEquals(new BytesRef(InetAddressPoint.encode(InetAddresses.forString("::1"))), dvField.binaryValue());
        assertFalse(dvField.fieldType().stored());
    }

    public void testNotIndexed() throws Exception {
        String mapping = XContentFactory.jsonBuilder().startObject().startObject("type")
                .startObject("properties").startObject("field").field("type", "ip").field("index", false).endObject().endObject()
                .endObject().endObject().string();

        DocumentMapper mapper = parser.parse("type", new CompressedXContent(mapping));

        assertEquals(mapping, mapper.mappingSource().toString());

        ParsedDocument doc = mapper.parse("test", "type", "1", XContentFactory.jsonBuilder()
                .startObject()
                .field("field", "::1")
                .endObject()
                .bytes());

        IndexableField[] fields = doc.rootDoc().getFields("field");
        assertEquals(1, fields.length);
        IndexableField dvField = fields[0];
        assertEquals(DocValuesType.SORTED_SET, dvField.fieldType().docValuesType());
    }

    public void testNoDocValues() throws Exception {
        String mapping = XContentFactory.jsonBuilder().startObject().startObject("type")
                .startObject("properties").startObject("field").field("type", "ip").field("doc_values", false).endObject().endObject()
                .endObject().endObject().string();

        DocumentMapper mapper = parser.parse("type", new CompressedXContent(mapping));

        assertEquals(mapping, mapper.mappingSource().toString());

        ParsedDocument doc = mapper.parse("test", "type", "1", XContentFactory.jsonBuilder()
                .startObject()
                .field("field", "::1")
                .endObject()
                .bytes());

        IndexableField[] fields = doc.rootDoc().getFields("field");
        assertEquals(1, fields.length);
        IndexableField pointField = fields[0];
        assertEquals(1, pointField.fieldType().pointDimensionCount());
        assertEquals(new BytesRef(InetAddressPoint.encode(InetAddresses.forString("::1"))), pointField.binaryValue());
    }

    public void testStore() throws Exception {
        String mapping = XContentFactory.jsonBuilder().startObject().startObject("type")
                .startObject("properties").startObject("field").field("type", "ip").field("store", true).endObject().endObject()
                .endObject().endObject().string();

        DocumentMapper mapper = parser.parse("type", new CompressedXContent(mapping));

        assertEquals(mapping, mapper.mappingSource().toString());

        ParsedDocument doc = mapper.parse("test", "type", "1", XContentFactory.jsonBuilder()
                .startObject()
                .field("field", "::1")
                .endObject()
                .bytes());

        IndexableField[] fields = doc.rootDoc().getFields("field");
        assertEquals(3, fields.length);
        IndexableField pointField = fields[0];
        assertEquals(1, pointField.fieldType().pointDimensionCount());
        IndexableField dvField = fields[1];
        assertEquals(DocValuesType.SORTED_SET, dvField.fieldType().docValuesType());
        IndexableField storedField = fields[2];
        assertTrue(storedField.fieldType().stored());
        assertEquals(new BytesRef(InetAddressPoint.encode(InetAddress.getByName("::1"))),
                storedField.binaryValue());
    }

    public void testIgnoreMalformed() throws Exception {
        String mapping = XContentFactory.jsonBuilder().startObject().startObject("type")
                .startObject("properties").startObject("field").field("type", "ip").endObject().endObject()
                .endObject().endObject().string();

        DocumentMapper mapper = parser.parse("type", new CompressedXContent(mapping));

        assertEquals(mapping, mapper.mappingSource().toString());

        ThrowingRunnable runnable = () -> mapper.parse("test", "type", "1", XContentFactory.jsonBuilder()
                .startObject()
                .field("field", ":1")
                .endObject()
                .bytes());
        MapperParsingException e = expectThrows(MapperParsingException.class, runnable);
        assertThat(e.getCause().getMessage(), containsString("':1' is not an IP string literal"));

        mapping = XContentFactory.jsonBuilder().startObject().startObject("type")
                .startObject("properties").startObject("field").field("type", "ip").field("ignore_malformed", true).endObject().endObject()
                .endObject().endObject().string();

        DocumentMapper mapper2 = parser.parse("type", new CompressedXContent(mapping));

        ParsedDocument doc = mapper2.parse("test", "type", "1", XContentFactory.jsonBuilder()
                .startObject()
                .field("field", ":1")
                .endObject()
                .bytes());

        IndexableField[] fields = doc.rootDoc().getFields("field");
        assertEquals(0, fields.length);
    }

    public void testIncludeInAll() throws Exception {
        String mapping = XContentFactory.jsonBuilder().startObject().startObject("type")
                .startObject("properties").startObject("field").field("type", "ip").endObject().endObject()
                .endObject().endObject().string();

        DocumentMapper mapper = parser.parse("type", new CompressedXContent(mapping));

        assertEquals(mapping, mapper.mappingSource().toString());

        ParsedDocument doc = mapper.parse("test", "type", "1", XContentFactory.jsonBuilder()
                .startObject()
                .field("field", "::1")
                .endObject()
                .bytes());

        IndexableField[] fields = doc.rootDoc().getFields("_all");
        assertEquals(1, fields.length);
        assertEquals("::1", fields[0].stringValue());

        mapping = XContentFactory.jsonBuilder().startObject().startObject("type")
                .startObject("properties").startObject("field").field("type", "ip")
                .field("include_in_all", false).endObject().endObject()
                .endObject().endObject().string();

        mapper = parser.parse("type", new CompressedXContent(mapping));

        assertEquals(mapping, mapper.mappingSource().toString());

        doc = mapper.parse("test", "type", "1", XContentFactory.jsonBuilder()
                .startObject()
                .field("field", "::1")
                .endObject()
                .bytes());

        fields = doc.rootDoc().getFields("_all");
        assertEquals(0, fields.length);
    }

    public void testNullValue() throws IOException {
        String mapping = XContentFactory.jsonBuilder().startObject()
                .startObject("type")
                    .startObject("properties")
                        .startObject("field")
                            .field("type", "ip")
                        .endObject()
                    .endObject()
                .endObject().endObject().string();

        DocumentMapper mapper = parser.parse("type", new CompressedXContent(mapping));
        assertEquals(mapping, mapper.mappingSource().toString());

        ParsedDocument doc = mapper.parse("test", "type", "1", XContentFactory.jsonBuilder()
                .startObject()
                .nullField("field")
                .endObject()
                .bytes());
        assertArrayEquals(new IndexableField[0], doc.rootDoc().getFields("field"));

        mapping = XContentFactory.jsonBuilder().startObject()
                .startObject("type")
                    .startObject("properties")
                        .startObject("field")
                            .field("type", "ip")
                            .field("null_value", "::1")
                        .endObject()
                    .endObject()
                .endObject().endObject().string();

        mapper = parser.parse("type", new CompressedXContent(mapping));
        assertEquals(mapping, mapper.mappingSource().toString());

        doc = mapper.parse("test", "type", "1", XContentFactory.jsonBuilder()
                .startObject()
                .nullField("field")
                .endObject()
                .bytes());
        IndexableField[] fields = doc.rootDoc().getFields("field");
        assertEquals(2, fields.length);
        IndexableField pointField = fields[0];
        assertEquals(1, pointField.fieldType().pointDimensionCount());
        assertEquals(16, pointField.fieldType().pointNumBytes());
        assertFalse(pointField.fieldType().stored());
        assertEquals(new BytesRef(InetAddressPoint.encode(InetAddresses.forString("::1"))), pointField.binaryValue());
        IndexableField dvField = fields[1];
        assertEquals(DocValuesType.SORTED_SET, dvField.fieldType().docValuesType());
        assertEquals(new BytesRef(InetAddressPoint.encode(InetAddresses.forString("::1"))), dvField.binaryValue());
        assertFalse(dvField.fieldType().stored());
    }

    public void testSerializeDefaults() throws Exception {
        String mapping = XContentFactory.jsonBuilder().startObject().startObject("type")
            .startObject("properties").startObject("field").field("type", "ip").endObject().endObject()
            .endObject().endObject().string();

        DocumentMapper docMapper = parser.parse("type", new CompressedXContent(mapping));
        IpFieldMapper mapper = (IpFieldMapper)docMapper.root().getMapper("field");
        XContentBuilder builder = XContentFactory.jsonBuilder().startObject();
        mapper.doXContentBody(builder, true, ToXContent.EMPTY_PARAMS);
        String got = builder.endObject().string();

        // it would be nice to check the entire serialized default mapper, but there are
        // a whole lot of bogus settings right now it picks up from calling super.doXContentBody...
        assertTrue(got, got.contains("\"null_value\":null"));
        assertTrue(got, got.contains("\"ignore_malformed\":false"));
        assertTrue(got, got.contains("\"include_in_all\":false"));
    }
}

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.olingo.commons.core.serialization;

import java.io.IOException;
import java.io.InputStream;
import java.util.AbstractMap.SimpleEntry;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.olingo.commons.api.Constants;
import org.apache.olingo.commons.api.data.Annotatable;
import org.apache.olingo.commons.api.data.Annotation;
import org.apache.olingo.commons.api.data.CollectionValue;
import org.apache.olingo.commons.api.data.ComplexValue;
import org.apache.olingo.commons.api.data.Entity;
import org.apache.olingo.commons.api.data.EntitySet;
import org.apache.olingo.commons.api.data.Linked;
import org.apache.olingo.commons.api.data.Property;
import org.apache.olingo.commons.api.data.ResWrap;
import org.apache.olingo.commons.api.data.Valuable;
import org.apache.olingo.commons.api.data.Value;
import org.apache.olingo.commons.api.domain.ODataError;
import org.apache.olingo.commons.api.domain.ODataLinkType;
import org.apache.olingo.commons.api.domain.ODataPropertyType;
import org.apache.olingo.commons.api.edm.EdmPrimitiveTypeKind;
import org.apache.olingo.commons.api.edm.constants.ODataServiceVersion;
import org.apache.olingo.commons.api.serialization.ODataDeserializer;
import org.apache.olingo.commons.api.serialization.ODataDeserializerException;
import org.apache.olingo.commons.core.data.AnnotationImpl;
import org.apache.olingo.commons.core.data.CollectionValueImpl;
import org.apache.olingo.commons.core.data.ComplexValueImpl;
import org.apache.olingo.commons.core.data.EntitySetImpl;
import org.apache.olingo.commons.core.data.EnumValueImpl;
import org.apache.olingo.commons.core.data.GeospatialValueImpl;
import org.apache.olingo.commons.core.data.LinkImpl;
import org.apache.olingo.commons.core.data.LinkedComplexValueImpl;
import org.apache.olingo.commons.core.data.NullValueImpl;
import org.apache.olingo.commons.core.data.PrimitiveValueImpl;
import org.apache.olingo.commons.core.data.PropertyImpl;
import org.apache.olingo.commons.core.edm.EdmTypeInfo;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class JsonDeserializer implements ODataDeserializer {

  protected final Pattern CUSTOM_ANNOTATION = Pattern.compile("(.+)@(.+)\\.(.+)");
  protected final ODataServiceVersion version;
  protected final boolean serverMode;

  protected String jsonType;
  protected String jsonId;
  protected String jsonETag;
  protected String jsonReadLink;
  protected String jsonEditLink;
  protected String jsonMediaEditLink;
  protected String jsonMediaReadLink;
  protected String jsonMediaContentType;
  protected String jsonMediaETag;
  protected String jsonAssociationLink;
  protected String jsonNavigationLink;
  protected String jsonCount;
  protected String jsonNextLink;
  protected String jsonDeltaLink;
  protected String jsonError;

  private JsonGeoValueDeserializer geoDeserializer;

  private JsonParser parser;

  public JsonDeserializer(final ODataServiceVersion version, final boolean serverMode) {
    this.version = version;
    this.serverMode = serverMode;

    jsonType = version.getJSONMap().get(ODataServiceVersion.JSON_TYPE);
    jsonId = version.getJSONMap().get(ODataServiceVersion.JSON_ID);
    jsonETag = version.getJSONMap().get(ODataServiceVersion.JSON_ETAG);
    jsonReadLink = version.getJSONMap().get(ODataServiceVersion.JSON_READ_LINK);
    jsonEditLink = version.getJSONMap().get(ODataServiceVersion.JSON_EDIT_LINK);
    jsonMediaReadLink = version.getJSONMap().get(ODataServiceVersion.JSON_MEDIAREAD_LINK);
    jsonMediaEditLink = version.getJSONMap().get(ODataServiceVersion.JSON_MEDIAEDIT_LINK);
    jsonMediaContentType = version.getJSONMap().get(ODataServiceVersion.JSON_MEDIA_CONTENT_TYPE);
    jsonMediaETag = version.getJSONMap().get(ODataServiceVersion.JSON_MEDIA_ETAG);
    jsonAssociationLink = version.getJSONMap().get(ODataServiceVersion.JSON_ASSOCIATION_LINK);
    jsonNavigationLink = version.getJSONMap().get(ODataServiceVersion.JSON_NAVIGATION_LINK);
    jsonCount = version.getJSONMap().get(ODataServiceVersion.JSON_COUNT);
    jsonNextLink = version.getJSONMap().get(ODataServiceVersion.JSON_NEXT_LINK);
    jsonDeltaLink = version.getJSONMap().get(ODataServiceVersion.JSON_DELTA_LINK);
    jsonError = version.getJSONMap().get(ODataServiceVersion.JSON_ERROR);
}

  private JsonGeoValueDeserializer getGeoDeserializer() {
    if (geoDeserializer == null) {
      geoDeserializer = new JsonGeoValueDeserializer(version);
    }
    return geoDeserializer;
  }

  protected String getJSONAnnotation(final String string) {
    return StringUtils.prependIfMissing(string, "@");
  }

  protected String getTitle(final Map.Entry<String, JsonNode> entry) {
    return entry.getKey().substring(0, entry.getKey().indexOf('@'));
  }

  protected String setInline(final String name, final String suffix, final JsonNode tree,
      final ObjectCodec codec, final LinkImpl link) throws IOException {

    final String entityNamePrefix = name.substring(0, name.indexOf(suffix));
    if (tree.has(entityNamePrefix)) {
      final JsonNode inline = tree.path(entityNamePrefix);
      JsonEntityDeserializer entityDeserializer = new JsonEntityDeserializer(version, serverMode);

      if (inline instanceof ObjectNode) {
        link.setType(ODataLinkType.ENTITY_NAVIGATION.toString());
        link.setInlineEntity(entityDeserializer.doDeserialize(inline.traverse(codec)).getPayload());

      } else if (inline instanceof ArrayNode) {
        link.setType(ODataLinkType.ENTITY_SET_NAVIGATION.toString());

        EntitySet entitySet = new EntitySetImpl();
        Iterator<JsonNode> entries = ((ArrayNode) inline).elements();
        while (entries.hasNext()) {
          entitySet.getEntities().add(
              entityDeserializer.doDeserialize(entries.next().traverse(codec)).getPayload());
        }

        link.setInlineEntitySet(entitySet);
      }
    }
    return entityNamePrefix;
  }

  protected void links(final Map.Entry<String, JsonNode> field, final Linked linked, final Set<String> toRemove,
      final JsonNode tree, final ObjectCodec codec) throws IOException {
    if (serverMode) {
      serverLinks(field, linked, toRemove, tree, codec);
    } else {
      clientLinks(field, linked, toRemove, tree, codec);
    }
  }

  private void clientLinks(final Map.Entry<String, JsonNode> field, final Linked linked, final Set<String> toRemove,
      final JsonNode tree, final ObjectCodec codec) throws IOException {

    if (field.getKey().endsWith(jsonNavigationLink)) {
      final LinkImpl link = new LinkImpl();
      link.setTitle(getTitle(field));
      link.setRel(version.getNamespaceMap().get(ODataServiceVersion.NAVIGATION_LINK_REL) + getTitle(field));

      if (field.getValue().isValueNode()) {
        link.setHref(field.getValue().textValue());
        link.setType(ODataLinkType.ENTITY_NAVIGATION.toString());
      }

      linked.getNavigationLinks().add(link);

      toRemove.add(field.getKey());
      toRemove.add(setInline(field.getKey(), jsonNavigationLink, tree, codec, link));
    } else if (field.getKey().endsWith(jsonAssociationLink)) {
      final LinkImpl link = new LinkImpl();
      link.setTitle(getTitle(field));
      link.setRel(version.getNamespaceMap().get(ODataServiceVersion.ASSOCIATION_LINK_REL) + getTitle(field));
      link.setHref(field.getValue().textValue());
      link.setType(ODataLinkType.ASSOCIATION.toString());
      linked.getAssociationLinks().add(link);

      toRemove.add(field.getKey());
    }
  }

  private void serverLinks(final Map.Entry<String, JsonNode> field, final Linked linked, final Set<String> toRemove,
      final JsonNode tree, final ObjectCodec codec) throws IOException {

    if (field.getKey().endsWith(Constants.JSON_BIND_LINK_SUFFIX)
        || field.getKey().endsWith(jsonNavigationLink)) {

      if (field.getValue().isValueNode()) {
        final String suffix = field.getKey().replaceAll("^.*@", "@");

        final LinkImpl link = new LinkImpl();
        link.setTitle(getTitle(field));
        link.setRel(version.getNamespaceMap().get(ODataServiceVersion.NAVIGATION_LINK_REL) + getTitle(field));
        link.setHref(field.getValue().textValue());
        link.setType(ODataLinkType.ENTITY_NAVIGATION.toString());
        linked.getNavigationLinks().add(link);

        toRemove.add(setInline(field.getKey(), suffix, tree, codec, link));
      } else if (field.getValue().isArray()) {
        for (final Iterator<JsonNode> itor = field.getValue().elements(); itor.hasNext();) {
          final JsonNode node = itor.next();

          final LinkImpl link = new LinkImpl();
          link.setTitle(getTitle(field));
          link.setRel(version.getNamespaceMap().get(ODataServiceVersion.NAVIGATION_LINK_REL) + getTitle(field));
          link.setHref(node.asText());
          link.setType(ODataLinkType.ENTITY_SET_NAVIGATION.toString());
          linked.getNavigationLinks().add(link);
          toRemove.add(setInline(field.getKey(), Constants.JSON_BIND_LINK_SUFFIX, tree, codec, link));
        }
      }
      toRemove.add(field.getKey());
    }
  }

  private Map.Entry<ODataPropertyType, EdmTypeInfo> guessPropertyType(final JsonNode node) {
    ODataPropertyType type;
    EdmTypeInfo typeInfo = null;

    if (node.isValueNode() || node.isNull()) {
      type = ODataPropertyType.PRIMITIVE;

      EdmPrimitiveTypeKind kind = EdmPrimitiveTypeKind.String;
      if (node.isShort()) {
        kind = EdmPrimitiveTypeKind.Int16;
      } else if (node.isInt()) {
        kind = EdmPrimitiveTypeKind.Int32;
      } else if (node.isLong()) {
        kind = EdmPrimitiveTypeKind.Int64;
      } else if (node.isBoolean()) {
        kind = EdmPrimitiveTypeKind.Boolean;
      } else if (node.isFloat()) {
        kind = EdmPrimitiveTypeKind.Single;
      } else if (node.isDouble()) {
        kind = EdmPrimitiveTypeKind.Double;
      } else if (node.isBigDecimal()) {
        kind = EdmPrimitiveTypeKind.Decimal;
      }
      typeInfo = new EdmTypeInfo.Builder().setTypeExpression(kind.getFullQualifiedName().toString()).build();
    } else if (node.isArray()) {
      type = ODataPropertyType.COLLECTION;
    } else if (node.isObject()) {
      if (node.has(Constants.ATTR_TYPE)) {
        type = ODataPropertyType.PRIMITIVE;
        typeInfo = new EdmTypeInfo.Builder().
            setTypeExpression("Edm.Geography" + node.get(Constants.ATTR_TYPE).asText()).build();
      } else {
        type = ODataPropertyType.COMPLEX;
      }
    } else {
      type = ODataPropertyType.EMPTY;
    }

    return new SimpleEntry<ODataPropertyType, EdmTypeInfo>(type, typeInfo);
  }

  protected void populate(final Annotatable annotatable, final List<Property> properties,
      final ObjectNode tree, final ObjectCodec codec) throws IOException {

    String type = null;
    Annotation annotation = null;
    for (final Iterator<Map.Entry<String, JsonNode>> itor = tree.fields(); itor.hasNext();) {
      final Map.Entry<String, JsonNode> field = itor.next();
      final Matcher customAnnotation = CUSTOM_ANNOTATION.matcher(field.getKey());

      if (field.getKey().charAt(0) == '@') {
        final Annotation entityAnnot = new AnnotationImpl();
        entityAnnot.setTerm(field.getKey().substring(1));

        value(entityAnnot, field.getValue(), codec);
        if (annotatable != null) {
          annotatable.getAnnotations().add(entityAnnot);
        }
      } else if (type == null && field.getKey().endsWith(getJSONAnnotation(jsonType))) {
        type = field.getValue().asText();
      } else if (annotation == null && customAnnotation.matches() && !"odata".equals(customAnnotation.group(2))) {
        annotation = new AnnotationImpl();
        annotation.setTerm(customAnnotation.group(2) + "." + customAnnotation.group(3));
        value(annotation, field.getValue(), codec);
      } else {
        final PropertyImpl property = new PropertyImpl();
        property.setName(field.getKey());
        property.setType(type == null
            ? null
            : new EdmTypeInfo.Builder().setTypeExpression(type).build().internal());
        type = null;

        value(property, field.getValue(), codec);
        properties.add(property);

        if (annotation != null) {
          property.getAnnotations().add(annotation);
          annotation = null;
        }
      }
    }
  }

  private Value fromPrimitive(final JsonNode node, final EdmTypeInfo typeInfo) {
    final Value value;

    if (node.isNull()) {
      value = new NullValueImpl();
    } else {
      if (typeInfo != null && typeInfo.getPrimitiveTypeKind().isGeospatial()) {
        value = new GeospatialValueImpl(getGeoDeserializer().deserialize(node, typeInfo));
      } else {
        value = new PrimitiveValueImpl(node.asText());
      }
    }

    return value;
  }

  private ComplexValue fromComplex(final ObjectNode node, final ObjectCodec codec) throws IOException {
    final ComplexValue value = version.compareTo(ODataServiceVersion.V40) < 0
        ? new ComplexValueImpl()
        : new LinkedComplexValueImpl();

    if (value.isLinkedComplex()) {
      final Set<String> toRemove = new HashSet<String>();
      for (final Iterator<Map.Entry<String, JsonNode>> itor = node.fields(); itor.hasNext();) {
        final Map.Entry<String, JsonNode> field = itor.next();

        links(field, value.asLinkedComplex(), toRemove, node, codec);
      }
      node.remove(toRemove);
    }

    populate(value.asLinkedComplex(), value.get(), node, codec);

    return value;
  }

  private CollectionValue fromCollection(final Iterator<JsonNode> nodeItor, final EdmTypeInfo typeInfo,
      final ObjectCodec codec) throws IOException {

    final CollectionValueImpl value = new CollectionValueImpl();

    final EdmTypeInfo type = typeInfo == null
        ? null
        : new EdmTypeInfo.Builder().setTypeExpression(typeInfo.getFullQualifiedName().toString()).build();

    while (nodeItor.hasNext()) {
      final JsonNode child = nodeItor.next();

      if (child.isValueNode()) {
        if (typeInfo == null || typeInfo.isPrimitiveType()) {
          value.get().add(fromPrimitive(child, type));
        } else {
          value.get().add(new EnumValueImpl(child.asText()));
        }
      } else if (child.isContainerNode()) {
        if (child.has(jsonType)) {
          ((ObjectNode) child).remove(jsonType);
        }
        value.get().add(fromComplex((ObjectNode) child, codec));
      }
    }

    return value;
  }

  protected void value(final Valuable valuable, final JsonNode node, final ObjectCodec codec)
      throws IOException {

    EdmTypeInfo typeInfo = StringUtils.isBlank(valuable.getType())
        ? null
        : new EdmTypeInfo.Builder().setTypeExpression(valuable.getType()).build();

    final Map.Entry<ODataPropertyType, EdmTypeInfo> guessed = guessPropertyType(node);
    if (typeInfo == null) {
      typeInfo = guessed.getValue();
    }

    final ODataPropertyType propType = typeInfo == null
        ? guessed.getKey()
        : typeInfo.isCollection()
            ? ODataPropertyType.COLLECTION
            : typeInfo.isPrimitiveType()
                ? ODataPropertyType.PRIMITIVE
                : node.isValueNode()
                    ? ODataPropertyType.ENUM
                    : ODataPropertyType.COMPLEX;

    switch (propType) {
    case COLLECTION:
      valuable.setValue(fromCollection(node.elements(), typeInfo, codec));
      break;

    case COMPLEX:
      if (node.has(jsonType)) {
        valuable.setType(node.get(jsonType).asText());
        ((ObjectNode) node).remove(jsonType);
      }
      valuable.setValue(fromComplex((ObjectNode) node, codec));
      break;

    case ENUM:
      valuable.setValue(new EnumValueImpl(node.asText()));
      break;

    case PRIMITIVE:
      if (valuable.getType() == null && typeInfo != null) {
        valuable.setType(typeInfo.getFullQualifiedName().toString());
      }
      valuable.setValue(fromPrimitive(node, typeInfo));
      break;

    case EMPTY:
    default:
      valuable.setValue(new PrimitiveValueImpl(StringUtils.EMPTY));
    }
  }

  @Override
  public ResWrap<EntitySet> toEntitySet(InputStream input) throws ODataDeserializerException {
    try {
      parser = new JsonFactory(new ObjectMapper()).createParser(input);
      return new JsonEntitySetDeserializer(version, serverMode).doDeserialize(parser);
    } catch (final IOException e) {
      throw new ODataDeserializerException(e);
    }
  }

  @Override
  public ResWrap<Entity> toEntity(InputStream input) throws ODataDeserializerException {
    try {
      parser = new JsonFactory(new ObjectMapper()).createParser(input);
      return new JsonEntityDeserializer(version, serverMode).doDeserialize(parser);
    } catch (final IOException e) {
      throw new ODataDeserializerException(e);
    }
  }

  @Override
  public ResWrap<Property> toProperty(InputStream input) throws ODataDeserializerException {
    try {
      parser = new JsonFactory(new ObjectMapper()).createParser(input);
      return new JsonPropertyDeserializer(version, serverMode).doDeserialize(parser);
    } catch (final IOException e) {
      throw new ODataDeserializerException(e);
    }
  }

  @Override
  public ODataError toError(InputStream input) throws ODataDeserializerException {
    try {
      parser = new JsonFactory(new ObjectMapper()).createParser(input);
      return new JsonODataErrorDeserializer(version, serverMode).doDeserialize(parser);
    } catch (final IOException e) {
      throw new ODataDeserializerException(e);
    }
  }
}
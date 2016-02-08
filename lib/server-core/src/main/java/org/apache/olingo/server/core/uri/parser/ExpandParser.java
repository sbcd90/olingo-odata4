/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.olingo.server.core.uri.parser;

import java.util.Map;

import org.apache.olingo.commons.api.edm.Edm;
import org.apache.olingo.commons.api.edm.EdmEntityType;
import org.apache.olingo.commons.api.edm.EdmNavigationProperty;
import org.apache.olingo.commons.api.edm.EdmProperty;
import org.apache.olingo.commons.api.edm.EdmStructuredType;
import org.apache.olingo.commons.api.edm.FullQualifiedName;
import org.apache.olingo.commons.api.edm.constants.EdmTypeKind;
import org.apache.olingo.commons.api.ex.ODataRuntimeException;
import org.apache.olingo.server.api.OData;
import org.apache.olingo.server.api.uri.UriInfoKind;
import org.apache.olingo.server.api.uri.UriResourceNavigation;
import org.apache.olingo.server.api.uri.UriResourcePartTyped;
import org.apache.olingo.server.api.uri.queryoption.AliasQueryOption;
import org.apache.olingo.server.api.uri.queryoption.ExpandItem;
import org.apache.olingo.server.api.uri.queryoption.ExpandOption;
import org.apache.olingo.server.api.uri.queryoption.LevelsExpandOption;
import org.apache.olingo.server.api.uri.queryoption.SystemQueryOption;
import org.apache.olingo.server.api.uri.queryoption.SystemQueryOptionKind;
import org.apache.olingo.server.core.uri.UriInfoImpl;
import org.apache.olingo.server.core.uri.UriResourceComplexPropertyImpl;
import org.apache.olingo.server.core.uri.UriResourceCountImpl;
import org.apache.olingo.server.core.uri.UriResourceNavigationPropertyImpl;
import org.apache.olingo.server.core.uri.UriResourceRefImpl;
import org.apache.olingo.server.core.uri.parser.UriTokenizer.TokenKind;
import org.apache.olingo.server.core.uri.queryoption.CountOptionImpl;
import org.apache.olingo.server.core.uri.queryoption.ExpandItemImpl;
import org.apache.olingo.server.core.uri.queryoption.ExpandOptionImpl;
import org.apache.olingo.server.core.uri.queryoption.LevelsOptionImpl;
import org.apache.olingo.server.core.uri.queryoption.SkipOptionImpl;
import org.apache.olingo.server.core.uri.queryoption.TopOptionImpl;
import org.apache.olingo.server.core.uri.validator.UriValidationException;

public class ExpandParser {

  private final Edm edm;
  private final OData odata;
  private final Map<String, AliasQueryOption> aliases;

  public ExpandParser(final Edm edm, final OData odata, final Map<String, AliasQueryOption> aliases) {
    this.edm = edm;
    this.odata = odata;
    this.aliases = aliases;
  }

  public ExpandOption parse(UriTokenizer tokenizer, final EdmStructuredType referencedType)
      throws UriParserException, UriValidationException {
    ExpandOptionImpl expandOption = new ExpandOptionImpl();
    do {
      final ExpandItem item = parseItem(tokenizer, referencedType);
      expandOption.addExpandItem(item);
    } while (tokenizer.next(TokenKind.COMMA));

    return expandOption;
  }

  private ExpandItem parseItem(UriTokenizer tokenizer, final EdmStructuredType referencedType)
      throws UriParserException, UriValidationException {
    ExpandItemImpl item = new ExpandItemImpl();
    if (tokenizer.next(TokenKind.STAR)) {
      item.setIsStar(true);
      if (tokenizer.next(TokenKind.SLASH)) {
        ParserHelper.requireNext(tokenizer, TokenKind.REF);
        item.setIsRef(true);
      } else if (tokenizer.next(TokenKind.OPEN)) {
        ParserHelper.requireNext(tokenizer, TokenKind.LEVELS);
        ParserHelper.requireNext(tokenizer, TokenKind.EQ);
        item.setSystemQueryOption((SystemQueryOption) parseLevels(tokenizer));
        ParserHelper.requireNext(tokenizer, TokenKind.CLOSE);
      }

    } else {
      final EdmStructuredType typeCast = parseTypeCast(tokenizer, referencedType);
      if (typeCast != null) {
        item.setTypeFilter(typeCast);
        ParserHelper.requireNext(tokenizer, TokenKind.SLASH);
      }

      UriInfoImpl resource = parseExpandPath(tokenizer, referencedType, item);

      UriResourcePartTyped lastPart = (UriResourcePartTyped) resource.getLastResourcePart();

      boolean hasSlash = false;
      if (tokenizer.next(TokenKind.SLASH)) {
        hasSlash = true;
        if (lastPart instanceof UriResourceNavigation) {
          UriResourceNavigationPropertyImpl navigationResource = (UriResourceNavigationPropertyImpl) lastPart;
          final EdmNavigationProperty navigationProperty = navigationResource.getProperty();
          final EdmStructuredType typeCastSuffix = parseTypeCast(tokenizer, navigationProperty.getType());
          if (typeCastSuffix != null) {
            if (navigationProperty.isCollection()) {
              navigationResource.setCollectionTypeFilter(typeCastSuffix);
            } else {
              navigationResource.setEntryTypeFilter(typeCastSuffix);
            }
            hasSlash = false;
          }
        }
      }

      final EdmStructuredType newReferencedType = (EdmStructuredType) lastPart.getType();
      final boolean newReferencedIsCollection = lastPart.isCollection();
      if (hasSlash || tokenizer.next(TokenKind.SLASH)) {
        if (tokenizer.next(TokenKind.REF)) {
          resource.addResourcePart(new UriResourceRefImpl());
          parseOptions(tokenizer, newReferencedType, newReferencedIsCollection, item, true, false);
        } else {
          ParserHelper.requireNext(tokenizer, TokenKind.COUNT);
          resource.addResourcePart(new UriResourceCountImpl());
          parseOptions(tokenizer, newReferencedType, newReferencedIsCollection, item, false, true);
        }
      } else {
        parseOptions(tokenizer, newReferencedType, newReferencedIsCollection, item, false, false);
      }

      item.setResourcePath(resource);
    }

    return item;
  }

  private EdmStructuredType parseTypeCast(UriTokenizer tokenizer, final EdmStructuredType referencedType)
      throws UriParserException {
    if (tokenizer.next(TokenKind.QualifiedName)) {
      final FullQualifiedName qualifiedName = new FullQualifiedName(tokenizer.getText());
      final EdmStructuredType type = referencedType instanceof EdmEntityType ?
          edm.getEntityType(qualifiedName) :
          edm.getComplexType(qualifiedName);
      if (type == null) {
        throw new UriParserSemanticException("Type '" + qualifiedName + "' not found.",
            UriParserSemanticException.MessageKeys.UNKNOWN_PART, qualifiedName.getFullQualifiedNameAsString());
      } else {
        if (!type.compatibleTo(referencedType)) {
          throw new UriParserSemanticException("The type cast '" + qualifiedName + "' is not compatible.",
              UriParserSemanticException.MessageKeys.INCOMPATIBLE_TYPE_FILTER, type.getName());
        }
      }
      return type;
    }
    return null;
  }

  private UriInfoImpl parseExpandPath(UriTokenizer tokenizer, final EdmStructuredType referencedType,
      ExpandItemImpl item) throws UriParserException {
    UriInfoImpl resource = new UriInfoImpl().setKind(UriInfoKind.resource);

    EdmStructuredType type = referencedType;
    String name = null;
    while (tokenizer.next(TokenKind.ODataIdentifier)) {
      name = tokenizer.getText();
      final EdmProperty property = referencedType.getStructuralProperty(name);
      if (property != null && property.getType().getKind() == EdmTypeKind.COMPLEX) {
        type = (EdmStructuredType) property.getType();
        UriResourceComplexPropertyImpl complexResource = new UriResourceComplexPropertyImpl(property);
        ParserHelper.requireNext(tokenizer, TokenKind.SLASH);
        final EdmStructuredType typeCast = parseTypeCast(tokenizer, type);
        if (typeCast != null) {
          complexResource.setTypeFilter(typeCast);
          ParserHelper.requireNext(tokenizer, TokenKind.SLASH);
          type = typeCast;
        }
        resource.addResourcePart(complexResource);
      }
    }

    final EdmNavigationProperty navigationProperty = type.getNavigationProperty(name);
    if (navigationProperty == null) {
      if (tokenizer.next(TokenKind.STAR)) {
        item.setIsStar(true);
      } else {
        throw new UriParserSemanticException(
            "Navigation Property '" + name + "' not found in type '" + type.getFullQualifiedName() + "'.",
            UriParserSemanticException.MessageKeys.EXPRESSION_PROPERTY_NOT_IN_TYPE, type.getName(), name);
      }
    } else {
      resource.addResourcePart(new UriResourceNavigationPropertyImpl(navigationProperty));
    }

    return resource;
  }

  private void parseOptions(UriTokenizer tokenizer,
      final EdmStructuredType referencedType, final boolean referencedIsCollection,
      ExpandItemImpl item,
      final boolean forRef, final boolean forCount) throws UriParserException, UriValidationException {
    if (tokenizer.next(TokenKind.OPEN)) {
      do {
        SystemQueryOption systemQueryOption;
        if (!forCount && tokenizer.next(TokenKind.COUNT)) {
          ParserHelper.requireNext(tokenizer, TokenKind.EQ);
          ParserHelper.requireNext(tokenizer, TokenKind.BooleanValue);
          CountOptionImpl countOption = new CountOptionImpl();
          countOption.setText(tokenizer.getText());
          countOption.setValue(Boolean.parseBoolean(tokenizer.getText()));
          systemQueryOption = countOption;

        } else if (!forRef && !forCount && tokenizer.next(TokenKind.EXPAND)) {
          ParserHelper.requireNext(tokenizer, TokenKind.EQ);
          systemQueryOption = new ExpandParser(edm, odata, aliases).parse(tokenizer, referencedType);

        } else if (tokenizer.next(TokenKind.FILTER)) {
          ParserHelper.requireNext(tokenizer, TokenKind.EQ);
          systemQueryOption = new FilterParser(edm, odata).parse(tokenizer, referencedType, null, aliases);

        } else if (!forRef && !forCount && tokenizer.next(TokenKind.LEVELS)) {
          ParserHelper.requireNext(tokenizer, TokenKind.EQ);
          systemQueryOption = (SystemQueryOption) parseLevels(tokenizer);

        } else if (!forCount && tokenizer.next(TokenKind.ORDERBY)) {
          ParserHelper.requireNext(tokenizer, TokenKind.EQ);
          systemQueryOption = new OrderByParser(edm, odata).parse(tokenizer, referencedType, null, aliases);

        } else if (tokenizer.next(TokenKind.SEARCH)) {
          ParserHelper.requireNext(tokenizer, TokenKind.EQ);
          systemQueryOption = new SearchParser().parse(tokenizer);

        } else if (!forRef && !forCount && tokenizer.next(TokenKind.SELECT)) {
          ParserHelper.requireNext(tokenizer, TokenKind.EQ);
          systemQueryOption = new SelectParser(edm).parse(tokenizer, referencedType, referencedIsCollection);

        } else if (!forCount && tokenizer.next(TokenKind.SKIP)) {
          ParserHelper.requireNext(tokenizer, TokenKind.EQ);
          ParserHelper.requireNext(tokenizer, TokenKind.IntegerValue);
          final int value = ParserHelper.parseNonNegativeInteger(SystemQueryOptionKind.SKIP.toString(),
              tokenizer.getText(), true);
          SkipOptionImpl skipOption = new SkipOptionImpl();
          skipOption.setText(tokenizer.getText());
          skipOption.setValue(value);
          systemQueryOption = skipOption;

        } else if (!forCount && tokenizer.next(TokenKind.TOP)) {
          ParserHelper.requireNext(tokenizer, TokenKind.EQ);
          ParserHelper.requireNext(tokenizer, TokenKind.IntegerValue);
          final int value = ParserHelper.parseNonNegativeInteger(SystemQueryOptionKind.TOP.toString(),
              tokenizer.getText(), true);
          TopOptionImpl topOption = new TopOptionImpl();
          topOption.setText(tokenizer.getText());
          topOption.setValue(value);
          systemQueryOption = topOption;

        } else {
          throw new UriParserSyntaxException("Allowed query option expected.",
              UriParserSyntaxException.MessageKeys.SYNTAX);
        }
        try {
          item.setSystemQueryOption(systemQueryOption);
        } catch (final ODataRuntimeException e) {
          throw new UriParserSyntaxException("Double system query option '" + systemQueryOption.getName() + "'.", e,
              UriParserSyntaxException.MessageKeys.DOUBLE_SYSTEM_QUERY_OPTION, systemQueryOption.getName());
        }
      } while (tokenizer.next(TokenKind.SEMI));
      ParserHelper.requireNext(tokenizer, TokenKind.CLOSE);
    }
  }

  private LevelsExpandOption parseLevels(UriTokenizer tokenizer) throws UriParserException {
    final LevelsOptionImpl option = new LevelsOptionImpl();
    if (tokenizer.next(TokenKind.MAX)) {
      option.setText(tokenizer.getText());
      option.setMax();
    } else {
      ParserHelper.requireNext(tokenizer, TokenKind.IntegerValue);
      option.setText(tokenizer.getText());
      option.setValue(
          ParserHelper.parseNonNegativeInteger(SystemQueryOptionKind.LEVELS.toString(), tokenizer.getText(), false));
    }
    return option;
  }
}
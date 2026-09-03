package com.example.verirag.integration.wecom;

import org.w3c.dom.Document;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;

/** 安全解析企业微信回调 XML，禁止 DTD 与外部实体。 */
public final class WeComXml {

    private WeComXml() {
    }

    public static String value(String xml, String tagName) {
        if (xml == null || xml.isBlank()) {
            return "";
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setExpandEntityReferences(false);
            Document document = factory.newDocumentBuilder()
                    .parse(new InputSource(new StringReader(xml)));
            NodeList nodes = document.getElementsByTagName(tagName);
            return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
        } catch (Exception ex) {
            throw new IllegalArgumentException("Invalid WeCom callback XML", ex);
        }
    }
}

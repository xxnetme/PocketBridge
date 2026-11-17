package org.xmlpull.v1;
import javax.xml.stream.*;
import java.io.InputStream;
/* Эмуляция Android Xml.newPullParser() (KXmlParser) через JDK StAX.
   Для DOCX семантика событий (START_TAG/END_TAG/TEXT/DOCDECL) эквивалентна. */
public class StubParser implements XmlPullParser {
    private XMLStreamReader r;
    private boolean started = false;
    public void setFeature(String name, boolean state) throws Exception { /* namespaces всегда включены */ }
    public void setInput(InputStream in, String encoding) throws Exception {
        XMLInputFactory f = XMLInputFactory.newInstance();
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        f.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.TRUE);
        r = f.createXMLStreamReader(in, encoding);
        started = false;
    }
    public int nextToken() throws Exception {
        if (!started) { started = true; if (r.getEventType() == XMLStreamConstants.START_DOCUMENT) return START_DOCUMENT; }
        if (!r.hasNext()) return END_DOCUMENT;
        r.next();
        switch (r.getEventType()) {
            case XMLStreamConstants.START_ELEMENT: return START_TAG;
            case XMLStreamConstants.END_ELEMENT: return END_TAG;
            case XMLStreamConstants.CHARACTERS:
            case XMLStreamConstants.SPACE: return TEXT;
            case XMLStreamConstants.CDATA: return CDSECT;
            case XMLStreamConstants.ENTITY_REFERENCE: return ENTITY_REF;
            case XMLStreamConstants.DTD: return DOCDECL;
            case XMLStreamConstants.END_DOCUMENT: return END_DOCUMENT;
            default: return TEXT;
        }
    }
    public String getName() { return r == null ? null : r.getLocalName(); }
    public String getText() { return r == null ? null : r.getText(); }
}

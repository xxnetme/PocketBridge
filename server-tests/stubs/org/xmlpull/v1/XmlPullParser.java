package org.xmlpull.v1;
import java.io.*;
/* Minimal stub: DOCX preview code compiles, but parsing is unsupported on desktop test rig. */
public interface XmlPullParser {
    int START_DOCUMENT=0, END_DOCUMENT=1, START_TAG=2, END_TAG=3, TEXT=4, CDSECT=5, ENTITY_REF=6, DOCDECL=16;
    String FEATURE_PROCESS_NAMESPACES="http://xmlpull.org/v1/doc/features.html#process-namespaces";
    void setFeature(String name, boolean state) throws Exception;
    void setInput(InputStream in, String encoding) throws Exception;
    int nextToken() throws Exception;
    String getName();
    String getText();
}

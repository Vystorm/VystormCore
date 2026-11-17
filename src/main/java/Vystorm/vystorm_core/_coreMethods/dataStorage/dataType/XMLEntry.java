package Vystorm.vystorm_core._coreMethods.dataStorage.dataType;

import Vystorm.vystorm_core._parsersDebuggerEtc.debugger.DebugMessages;
import org.xml.sax.InputSource;
import javax.xml.XMLConstants;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class XMLEntry implements DataEntry {

    private Path path;
    private String name;
    private String rootTag = "library";
    private Map<String, String> dataMap;


    public XMLEntry(Path path, String name) {
       this.path = path;
       this.name = name;
       this.dataMap = new HashMap<>();
    }

    public XMLEntry(Path path, String name, String rootTag) {
       this.path = path;
       this.name = name;
       this.rootTag = rootTag;
       this.dataMap = new HashMap<>();
    }

    @Override
    public FileConfiguration getEntry() {
       DebugMessages.mid("[XMLEntry] XMLEntry.getEntry() is deprecated and not supported.");
       return null;
    }

    @Override
    public Path getPath() {
       return path;
    }

    @Override
    public String getName() {
       return name;
    }

    @Override
    public int getDataType() {
       return 5;
    }

    @Override
    public void setName(String name) {
       this.name = name;
    }

    @Override
    public void setPath(Path path) {
       this.path = path;
    }

    @Override
    public void setEntry(FileConfiguration entry) {
       DebugMessages.mid("[XMLEntry] XMLEntry.setEntry(FileConfiguration) is deprecated and not supported.");
    }
    
    @Override
    public void loadEntry() {
       try {
          if (path != null && Files.exists(path)) {
               String content = Files.readString(path);
               parseXML(content);
               DebugMessages.low("[XMLEntry] XML entry loaded; name=" + name);
          } else {
               dataMap = new HashMap<>();
          }
       } catch (Exception e) {
          DebugMessages.high("[XMLEntry] Failed to load XML entry; name=" + name);
          throw new RuntimeException("Failed to load XML entry: " + name, e);
       }
    }

    private void parseXML(String xmlContent) {
       try {
          DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
          factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
          factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
          factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
          factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
          DocumentBuilder builder = factory.newDocumentBuilder();
          Document doc = builder.parse(new InputSource(new StringReader(xmlContent)));
          Element root = doc.getDocumentElement();
            
          dataMap = new HashMap<>();
          NodeList nodeList = root.getChildNodes();
          for (int i = 0; i < nodeList.getLength(); i++) {
               Node node = nodeList.item(i);
               if (node.getNodeType() == Node.ELEMENT_NODE) {
                   Element element = (Element) node;
                   String key = element.getTagName();
                   String value = element.getTextContent();
                   dataMap.put(key, value);
               }
          }
       } catch (Exception e) {
          throw new RuntimeException("Failed to parse XML: " + e.getMessage(), e);
       }
    }

    @Override
    public String getEntry(String key) {
       if (dataMap == null) {
          loadEntry();
       }
       return dataMap.getOrDefault(key, null);
    }

    @Override
    public void setEntry(String key, String value) {
       if (dataMap == null) {
          loadEntry();
       }
       if (value != null) {
          dataMap.put(key, value);
       }
    }

    @Override
    public void saveEntry() {
       try {
          if (dataMap == null) {
               return;
          }

          if (path == null) {
               throw new IllegalStateException("Path is not set for XMLEntry: " + name);
          }
            
          if (path.getParent() != null && !Files.exists(path.getParent())) {
               Files.createDirectories(path.getParent());
          }
            
          String xmlContent = buildXML();
          Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFiles.write(path, xmlContent.getBytes(java.nio.charset.StandardCharsets.UTF_8));
          DebugMessages.low("[XMLEntry] XML entry saved; name=" + name);
       } catch (Exception e) {
          DebugMessages.high("[XMLEntry] Failed to save XML entry; name=" + name);
          throw new RuntimeException("Failed to save XML entry: " + name, e);
       }
    }

    private String buildXML() {
       try {
          DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
          DocumentBuilder builder = factory.newDocumentBuilder();
          Document doc = builder.newDocument();
          Element root = doc.createElement(rootTag);
          doc.appendChild(root);
            
          for (Map.Entry<String, String> entry : dataMap.entrySet()) {
               Element element = doc.createElement(entry.getKey());
               if (entry.getValue() != null) {
                   element.setTextContent(entry.getValue());
               }
               root.appendChild(element);
          }
            
          TransformerFactory transformerFactory = TransformerFactory.newInstance();
          Transformer transformer = transformerFactory.newTransformer();
          transformer.setOutputProperty(OutputKeys.INDENT, "yes");
          transformer.setOutputProperty(OutputKeys.METHOD, "xml");
          transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            
          DOMSource source = new DOMSource(doc);
          StringWriter writer = new StringWriter();
          StreamResult result = new StreamResult(writer);
          transformer.transform(source, result);
            
          return writer.toString();
       } catch (Exception e) {
          throw new RuntimeException("Failed to build XML: " + e.getMessage(), e);
       }
    }

    @Override
    public boolean containsEntry(String key) {
       if (dataMap == null) {
          loadEntry();
       }
       return dataMap.containsKey(key);
    }

    @Override
    public ConfigurationSection getSection(String key) {
       DebugMessages.mid("[XMLEntry] XMLEntry.getSection(String) is not supported. Use getEntry(String) instead.");
       return null;
    }

    @Override
    public void removeEntry(String key) {
       if (dataMap == null) {
          loadEntry();
       }
       dataMap.remove(key);
    }


}

package updater.process;

import arc.files.Fi;
import arc.files.ZipFi;
import arc.func.Cons;
import arc.struct.OrderedSet;
import arc.util.OS;
import org.apache.tools.ant.filters.StringInputStream;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import updater.Vars;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.StringWriter;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

public class ProjectProcessor {
    public static void process(String name, ZipFi zip, String tagName) throws IOException, InterruptedException, NoSuchAlgorithmException, SAXException {
        Fi innerFolder = zip.list()[0];
        Fi sourceFolder = Vars.sources.child(innerFolder.name());
sourceFolder.deleteDirectory();
        unzip:
        {
            String prefix = innerFolder.absolutePath() + "/";
            innerFolder.walk(it -> {
                it.copyTo(sourceFolder.child(it.absolutePath().substring(prefix.length())));
            });
        }
        Fi tmpRepository = Vars.sources.child("tmpRepository");
        tmpRepository.deleteDirectory();

        initBuildSrc:
        {
            Fi buildSrc = sourceFolder.child("buildSrc");
            Vars.innerBuildSrc.copyFilesTo(buildSrc);
            Vars.innerBuildSrc.child("gradlew.sh").copyTo(sourceFolder.child("gradlew.sh"));
            Fi taskFile = buildSrc.child("src/main/java/maven2github/PublishToGithubTask.java");
            taskFile.writeString(taskFile.readString().replace("File targetFolder = new File(getProject().getRootProject().getBuildDir(), \"mavenLocal\");",
                    "File targetFolder = new File(\"" + tmpRepository.absolutePath() + "\");"

            ));
        }

        setupPlugin:
        {
            Fi buildFile = sourceFolder.child("build.gradle");
            String string = buildFile.readString();
            int index = string.indexOf("allprojects");
            String newString = string.substring(0, index) + "apply plugin: maven2github.PublishGithubPlugin\n" + string.substring(index);

            newString = newString.replace("group = 'com.github.Anuken'", "group = 'com.github.Anuken." + name + "'")
                    .replaceAll("version ?= ?[^\n]+\n", "version = '" + tagName + "'\n")
                    .replace("withJavadocJar()", "//withJavadocJar()")
            ;
            buildFile.writeString(newString);


            String readString = sourceFolder.child("gradle.properties").readString();
            int index1 = readString.indexOf("archash");
            if(index1>=0){
                int index2 = readString.indexOf("\n", index1);
                if(index2<0)index2=readString.length();
                String s = readString.substring(0, index1) + "archash=" + tagName + readString.substring(index2);
                sourceFolder.child("gradle.properties").writeString(s);
            }
        }

        enforceCompatibleGradleWrapper:
        {
            Fi wrapperProperties = sourceFolder.child("gradle/wrapper/gradle-wrapper.properties");
            if(wrapperProperties.exists()){
                String content = wrapperProperties.readString();
                String updated = content.replaceAll("distributionUrl=.*", "distributionUrl=https\\://services.gradle.org/distributions/gradle-8.10-bin.zip");
                if(!content.equals(updated)){
                    wrapperProperties.writeString(updated);
                }
            }
        }

        System.out.println("gradlew publishFolder");
        ProcessBuilder pb;
        if(OS.isLinux){
            Runtime.getRuntime()
                    .exec("chmod +x gradlew",null,sourceFolder.file());
            pb = new ProcessBuilder(sourceFolder.absolutePath() + "/gradlew", "publishFolder", "--stacktrace");
        }else {
            pb = new ProcessBuilder(sourceFolder.absolutePath() + "/gradlew.bat", "publishFolder", "--stacktrace");
        }
        pb.directory(sourceFolder.file());
//        pb.inheritIO();
        Fi build_log_file = Vars.sources.child("build.log");
        pb.redirectError(ProcessBuilder.Redirect.to(build_log_file.file()));
        pb.redirectOutput(ProcessBuilder.Redirect.to(build_log_file.file()));
//        pb.redirectOutput(new ProcessBuilder().redirectOutput());
        /*FileDescriptor.out
        new File()
        pb.redirectError(new ProcessBuilder.Redirect.to(new File()))*/
//        pb.inheritIO();

        /*new Thread(() -> {
            byte[] buffer = new byte[1 << 13];
            while (p.isAlive()) {
                try {
                    int n = p.getInputStream().read(buffer, 0, buffer.length);
                    if (n > 0) System.out.write(buffer, 0, n);
                } catch (Exception e) {
                    continue;
                }
            }
        }).start();
        new Thread(() -> {
            byte[] buffer = new byte[1 << 13];
            while (p.isAlive()) {
                try {
                    int n = p.getErrorStream().read(buffer, 0, buffer.length);
                    if (n > 0) System.err.write(buffer, 0, n);
                } catch (Exception e) {
                    continue;
                }
            }
        }).start();*/
        if(OS.isWindows){
            System.out.println("This is test thing, you should run `gradlew publishFolder --stacktrace` manually");
            System.in.read();
        }else{
            Process p = pb.start();
            int processResult=            p.waitFor();
            if(processResult!=0){
                System.err.println("build.log : ");
                System.err.println(build_log_file.readString());
                throw new RuntimeException("Error while building");
            }
        }
        System.out.println("Processing maven repo");
        tmpRepository.walk(it -> {
            if (!it.name().matches("core-v\\d+\\.pom")) return;
            String string = it.readString();
            int index = string.indexOf("<artifactId>rhino</artifactId>");
            if (index == -1) return;
            int a = string.lastIndexOf("<dependency>", index);
            if (a < 0) return;
            int b = string.indexOf("</dependency>", index);
            if (b < 0) return;
            it.writeString(string.substring(0, a) + string.substring(b + "</dependency>".length()));
        });
        RenameLocalPom.process(tmpRepository);


        {
            String prefix = tmpRepository.absolutePath() + "/";
            System.out.println("tmpRepository:"+Arrays.toString(tmpRepository.list()));
            tmpRepository.walk(it -> {
                String path = it.absolutePath();
                System.out.println(path);
                Fi child = Vars.repository.child(path.substring(prefix.length()));
                if (!it.name().equals("maven-metadata.xml")) {
                    it.copyTo(child);
                } else {
                    try {
                        if(child.exists()) child.writeString(add(child.readString(), tagName));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            });
        }
    }

    static String add(String current, String newTag) throws SAXException, IOException, ParserConfigurationException, TransformerException {
        DocumentBuilder documentBuilder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        // Создается дерево DOM документа из файла
        Document document = documentBuilder.parse(new StringInputStream(current));

        // Получаем корневой элемент
        org.w3c.dom.Node root = document.getDocumentElement();

        for (Node rootChild = root.getFirstChild(); rootChild != null; rootChild = rootChild.getNextSibling()) {
            if (!rootChild.getNodeName().equals("versioning")) continue;

            for (Node versioningChild = rootChild.getFirstChild(); versioningChild != null; versioningChild = versioningChild.getNextSibling()) {

                if (versioningChild.getNodeName().matches("latest|release")) {
                    versioningChild.setTextContent(newTag);
                    versioningChild.setNodeValue(newTag);
                    continue;
                }
                if (versioningChild.getNodeName().equals("versions")) {
                    OrderedSet<String> set = new OrderedSet<>();
                    for (Node versionNode = versioningChild.getFirstChild(); versionNode != null; versionNode = versionNode.getNextSibling()) {
                        if (versionNode.getNodeName().equals("version") && !set.add(versionNode.getTextContent().trim())) {
                            versioningChild.removeChild(versionNode);
                        }
                    }
                    if (set.add(newTag)) {
                        Element version = document.createElement("version");
                        version.setTextContent(newTag);
                        versioningChild.appendChild(version);
                    }
                }
            }

        }

        Cons<Node>[] cons = new Cons[]{null};
        cons[0]=it->{
            it.setNodeValue(it.getTextContent().trim());
            NodeList childNodes = it.getChildNodes();
            for (int i = 0; i < childNodes.getLength(); i++) {
                cons[0].get(childNodes.item(i));
            }
        };
        cons[0].get(root);
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
//        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
//        transformer.setOutputProperty(OutputKeys.METHOD, "xml");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
//        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
//        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");
        DOMSource source = new DOMSource(document);
        StringWriter writer = new StringWriter();
        transformer.transform(source, new StreamResult(writer));
        return writer.toString();
    }
}

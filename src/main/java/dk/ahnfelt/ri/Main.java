package dk.ahnfelt.ri;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.codehaus.plexus.util.xml.pull.XmlPullParserException;

import java.io.*;
import java.util.*;

public class Main {
    private static class Project {
        private final String groupId;
        private final String artifactId;
        private final List<Project> dependencies;

        public Project(String groupId, String artifactId, List<Project> dependencies) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.dependencies = dependencies;
        }

        public Project(String groupId, String artifactId) {
            this(groupId, artifactId, null);
        }

        public String toString() {
            return getLinkName();
        }

        public String getLinkName() {
            return (groupId + ":" + artifactId).replace("/", "_");
        }

        public String getSourcePath() {
            String name = getLinkName();
            File home = new File(System.getProperty("user.home"));
            File links = new File(new File(home, ".ri"), "links");
            File link = new File(links, name);
            if(link.exists()) {
                try {
                    BufferedReader reader = new BufferedReader(new FileReader(link));
                    String line = reader.readLine();
                    reader.close();
                    return line;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            } else {
                return null;
            }
        }
    }

    private static Project pom(String directory) {
        File pom = new File(directory, "pom.xml");
        try {
            Reader reader = new FileReader(pom);
            Model model;
            try {
                MavenXpp3Reader xpp3Reader = new MavenXpp3Reader();
                model = xpp3Reader.read(reader);
            } finally {
                reader.close();
            }
            List<Project> dependencies = new LinkedList<Project>();
            for(Dependency dependency: (List<Dependency>) model.getDependencies()) {
                dependencies.add(new Project(dependency.getGroupId(), dependency.getArtifactId()));
            }
            return new Project(model.getGroupId(), model.getArtifactId(), dependencies);
        } catch(IOException e) {
            throw new RuntimeException(e);
        } catch(XmlPullParserException e) {
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] arguments) {
        arguments = arguments.length == 0 ? new String[] {"install"} : arguments;
        String command;
        if(arguments[0].startsWith("-")) {
            command = "install";
        } else {
            command = arguments[0];
            arguments = Arrays.copyOfRange(arguments, 1, arguments.length);
        }
        if(arguments.length == 0) {
            arguments = new String[] {"-o", "-DskipTests"};
        }
        if(command.equals("install")) {
            install(arguments);
        } else if(command.equals("link")) {
            link();
        } else if(command.equals("unlink")) {
            unlink(arguments);
        } else if(command.equals("list")) {
            list();
        } else if(command.equals("help")) {
            help();
        } else {
            System.err.println("Unknown command: " + command + " (for documentation, run: ri help)");
            System.exit(1);
        }
    }

    private static void help() {
        System.out.println("ri - recursive install of local dependencies for Maven 2");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  ri          (same as ri install)");
        System.out.println("  ri install  (recursive install of linked dependencies and current project)");
        System.out.println("  ri link     (link the current artifact to the current folder)");
        System.out.println("  ri unlink   (unlink the current or supplied artifact)");
        System.out.println("  ri list     (list all the linked artifacts)");
        System.out.println("  ri help     (this is what you're looking at)");
        System.out.println();
        System.out.println("Everything after the command will be passed to Maven.");
        System.out.println("If nothing, Maven will be called with -o -DskipTests.");
    }

    private static void unlink(String[] arguments) {
        String name;
        if(arguments.length > 0 && arguments[0].contains(":")) {
            name = arguments[0].replace('/', '_');
        } else {
            name = pom(System.getProperty("user.dir")).getLinkName();
        }
        File home = new File(System.getProperty("user.home"));
        File links = new File(new File(home, ".ri"), "links");
        File link = new File(links, name);
        if(link.delete()) {
            System.out.println("Unlinking " + name);
        } else {
            System.out.println("Nothing to unlink for " + name);
        }
    }

    private static void link() {
        File home = new File(System.getProperty("user.home"));
        File links = new File(new File(home, ".ri"), "links");
        links.mkdirs();
        Project specification = pom(System.getProperty("user.dir"));
        System.out.println("Linking " + specification);
        File link = new File(links, specification.getLinkName());
        try {
            Writer writer = new BufferedWriter(new FileWriter(link));
            writer.write(System.getProperty("user.dir"));
            writer.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void list() {
        File home = new File(System.getProperty("user.home"));
        File links = new File(new File(home, ".ri"), "links");
        File[] files = links.listFiles();
        if(files == null) return;
        for(File link: files) {
            System.out.println(link.getName());
        }
    }

    private static List<String> maven(String path, String command, String... arguments) {
        try {
            List<String> commandLine = new LinkedList<String>();
            commandLine.add("mvn");
            commandLine.add(command);
            commandLine.addAll(Arrays.asList(arguments));
            File directory = new File(path);
            ProcessBuilder processBuilder = new ProcessBuilder(commandLine).directory(directory);
            Process process = processBuilder.start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            List<String> packages = new LinkedList<String>();
            StringBuilder builder = new StringBuilder();
            String line;
            while((line = reader.readLine()) != null) {
                builder.append(line);
                builder.append('\n');
            }
            reader.close();
            int result = process.waitFor();
            if(result != 0) {
                System.err.println(builder);
                System.err.println("Could not run " + processBuilder.command() + " in " + processBuilder.directory());
                System.exit(1);
            }
            return packages;
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private static void install(String[] arguments) {
        Set<String> finished = new HashSet<String>();
        Project specification = pom(System.getProperty("user.dir"));
        finished.add(specification.getLinkName());
        recursiveInstall(specification, finished, arguments);
        System.out.println("Installing " + specification);
        maven(System.getProperty("user.dir"), "install", arguments);
    }

    private static void performInstall(Project specification, String[] arguments) {
        maven(specification.getSourcePath(), "install", arguments);
    }

    private static void recursiveInstall(Project project, Set<String> finished, String[] arguments) {
        if(project.dependencies == null) {
            String path = project.getSourcePath();
            if(path == null) return;
            project = pom(path);
        }
        for(Project dependency: project.dependencies) {
            if(!finished.contains(dependency.getLinkName())) {
                recursiveInstall(dependency, finished, arguments);
            }
        }
        if(!finished.contains(project.getLinkName())) {
            System.out.println("Installing " + project);
            performInstall(project, arguments);
            finished.add(project.getLinkName());
        }
    }
}


// Copyright © 2013-2015 Esko Luontola <www.orfjackal.net>
// This software is released under the Apache License 2.0.
// The license text is at http://www.apache.org/licenses/LICENSE-2.0

package net.orfjackal.retrolambda;

import net.orfjackal.retrolambda.files.*;
import net.orfjackal.retrolambda.interfaces.*;
import net.orfjackal.retrolambda.lambdas.*;
import net.orfjackal.retrolambda.util.Bytecode;

import java.io.IOException;
import java.net.*;
import java.nio.file.*;
import java.util.*;

public class Retrolambda {

    public static void run(Config config) throws Throwable {
        int bytecodeVersion = config.getBytecodeVersion();
        boolean defaultMethodsEnabled = config.isDefaultMethodsEnabled();
        Path inputDir = config.getInputDir();
        Path outputDir = config.getOutputDir();
        List<Path> classpath = config.getClasspath();
        List<Path> includedFiles = config.getIncludedFiles();
        System.out.println("Bytecode version: " + bytecodeVersion + " (" + Bytecode.getJavaVersion(bytecodeVersion) + ")");
        System.out.println("Default methods:  " + defaultMethodsEnabled);
        System.out.println("Input directory:  " + inputDir);
        System.out.println("Output directory: " + outputDir);
        System.out.println("Classpath:        " + classpath);
        System.out.println("Included files:   " + (includedFiles != null ? includedFiles.size() : "all"));
        System.out.println("Agent enabled:    " + PreMain.isAgentLoaded());

        if (!Files.isDirectory(inputDir)) {
            System.out.println("Nothing to do; not a directory: " + inputDir);
            return;
        }

        Thread.currentThread().setContextClassLoader(new NonDelegatingClassLoader(asUrls(classpath)));

        ClassAnalyzer analyzer = new ClassAnalyzer();
        OutputDirectory outputDirectory = new OutputDirectory(outputDir);
        Transformers transformers = new Transformers(bytecodeVersion, defaultMethodsEnabled, analyzer);
        LambdaClassSaver lambdaClassSaver = new LambdaClassSaver(outputDirectory, transformers);

        try (LambdaClassDumper dumper = new LambdaClassDumper(lambdaClassSaver)) {
            if (PreMain.isAgentLoaded()) {
                PreMain.setLambdaClassSaver(lambdaClassSaver);
            } else {
                dumper.install();
            }

            visitFiles(inputDir, includedFiles, new ClasspathVisitor() {
                @Override
                protected void visitClass(byte[] bytecode) {
                    analyzer.analyze(bytecode);
                }

                @Override
                protected void visitResource(Path relativePath, byte[] content) throws IOException {
//                    outputDirectory.writeFile(relativePath, content);
                }
            });

            // Because Transformers.backportLambdaClass() analyzes the lambda class,
            // adding it to the analyzer's list of classes, we must take care to
            // use the list of classes before that happened, or else we might accidentally
            // overwrite the lambda class.
            List<ClassInfo> interfaces = analyzer.getInterfaces();
            List<ClassInfo> classes = analyzer.getClasses();

            List<byte[]> transformed = new ArrayList<>();
            for (ClassInfo c : interfaces) {
                transformed.addAll(transformers.backportInterface(c.reader));
            }
            for (ClassInfo c : classes) {
                try {
					transformed.add(transformers.backportClass(c.reader));
				} catch (Exception e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
            }

            // We need to load some of the classes (for calling the lambda metafactory)
            // so we need to take care not to modify any bytecode before loading them.
            for (byte[] bytecode : transformed) {
                outputDirectory.writeClass(bytecode);
            }
        }
    }

    static void visitFiles(Path inputDir, List<Path> includedFiles, FileVisitor<Path> visitor) throws IOException {
//        if (includedFiles != null) {
//            visitor = new FilteringFileVisitor(includedFiles, visitor);
//        }
        Files.walkFileTree(inputDir, visitor);
    }

    private static URL[] asUrls(List<Path> classpath) {
        return classpath.stream()
                .map(Path::toUri)
                .map(Retrolambda::uriToUrl)
                .toArray(URL[]::new);
    }

    private static URL uriToUrl(URI uri) {
        try {
            return uri.toURL();
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }
}

/**
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

package org.apache.cxf.maven_plugin;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.filter.ScopeArtifactFilter;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectBuilder;
import org.apache.maven.shared.downloader.Downloader;

/**
 * @description CXF XSD To Java Tool
 */
public abstract class AbstractXSDToJavaMojo extends AbstractMojo {
    /**
     * @parameter expression="${project}"
     * @required
     */
    MavenProject project;
    
    
    /**
     * @parameter
     */
    XsdOption xsdOptions[];
    
    /**
     * Directory in which the "DONE" markers are saved that 
     * @parameter expression="${cxf.markerDirectory}" 
     *            default-value="${project.build.directory}/cxf-xsd-plugin-markers"
     */
    File markerDirectory;
    
    
    /**
     * The extension artifacts that will be retrieved and added to the classpath.
     *
     * @parameter
     */
    private List<String> extensions;
    
    
    /**
     * Artifact downloader.
     *
     * @component
     * @readonly
     * @required
     */
    private Downloader downloader;

    /**
     * The local repository taken from Maven's runtime. Typically $HOME/.m2/repository.
     *
     * @parameter expression="${localRepository}"
     * @readonly
     * @required
     */
    private ArtifactRepository localRepository;

    /**
     * List of Remote Repositories used by the resolver
     *
     * @parameter expression="${project.remoteArtifactRepositories}"
     * @readonly
     * @required
     */
    private List<ArtifactRepository> remoteArtifactRepositories;
    

    /**
     * Project builder -- builds a model from a pom.xml
     *
     * @component role="org.apache.maven.project.MavenProjectBuilder"
     * @required
     * @readonly
     */
    private MavenProjectBuilder mavenProjectBuilder;


    /**
     * Artifact factory, needed to download source jars for inclusion in classpath.
     *
     * @component role="org.apache.maven.artifact.factory.ArtifactFactory"
     * @required
     * @readonly
     */
    private ArtifactFactory artifactFactory;

    
    abstract String getOutputDir();
    
    
    private URI mapLocation(String s) throws MojoExecutionException {
        try {
            File file = new File(s);
            URI uri;
            if (file.exists()) {
                uri = file.toURI();
            } else {
                file = new File(project.getBasedir(), s);
                if (file.exists()) {
                    uri = file.toURI();
                } else {
                    uri = new URI(s);
                }
            }
            if ("classpath".equals(uri.getScheme())) {
                URL url = Thread.currentThread().getContextClassLoader()
                    .getResource(s.substring(10));
                if (url == null) {
                    url = Thread.currentThread().getContextClassLoader()
                        .getResource(s.substring(11));
                }
                if (url != null) {
                    uri = url.toURI();
                }
            }
            return uri;
        } catch (URISyntaxException e1) {
            throw new MojoExecutionException("Could not map " + s, e1);
        }
    }
    public void execute() throws MojoExecutionException {
        String outputDir = getOutputDir();
        
        File outputDirFile = new File(outputDir);
        outputDirFile.mkdirs();
        markerDirectory.mkdirs();

        boolean result = true;
        
        if (xsdOptions == null) {
            throw new MojoExecutionException("Must specify xsdOptions");           
        }
    
        for (int x = 0; x < xsdOptions.length; x++) {
            ClassLoader origLoader = Thread.currentThread().getContextClassLoader();
            try {
                String[] args = getArguments(xsdOptions[x], outputDir);
                URI xsdURI = mapLocation(xsdOptions[x].getXsd());
                URI basedir = project.getBasedir().toURI();
                
                String doneFileName = xsdURI.toString();
                if (doneFileName.startsWith(basedir.toString())) {
                    doneFileName = doneFileName.substring(basedir.toString().length());
                }
                
                doneFileName = doneFileName.replace('?', '_')
                    .replace('&', '_').replace('/', '_').replace('\\', '_');
                
                // If URL to WSDL, replace ? and & since they're invalid chars for file names
                File doneFile =
                    new File(markerDirectory, "." + doneFileName + ".DONE");
                
                long srctimestamp = 0;
                if ("file".equals(xsdURI.getScheme())) {
                    srctimestamp = new File(xsdURI).lastModified();
                } else {
                    try {
                        srctimestamp = xsdURI.toURL().openConnection().getDate();
                    } catch (Exception e) {
                        //ignore
                    }
                }
                
                boolean doWork = false;
                if (!doneFile.exists()) {
                    doWork = true;
                } else if (srctimestamp > doneFile.lastModified()) {
                    doWork = true;
                } else {
                    File files[] = xsdOptions[x].getDependencies();
                    if (files != null) {
                        for (int z = 0; z < files.length; ++z) {
                            if (files[z].lastModified() > doneFile.lastModified()) {
                                doWork = true;
                            }
                        }
                    }
                }
                
                if (doWork) {
                    try {
                        int i = com.sun.tools.xjc.Driver.run(args, System.out, System.err);
                        if (i == 0) {
                            doneFile.delete();
                            doneFile.createNewFile();
                        }
                        File dirs[] = xsdOptions[x].getDeleteDirs();
                        if (dirs != null) {
                            for (int idx = 0; idx < dirs.length; ++idx) {
                                result = result && deleteDir(dirs[idx]);
                            }
                        }
                    } catch (Exception e) {
                        throw new MojoExecutionException(e.getMessage(), e);
                    }
                }
            
                if (!result) {
                    throw new MojoExecutionException("Could not delete redundant dirs");
                }  
            } finally {
                Thread.currentThread().setContextClassLoader(origLoader);
            }
        }
    }
    
    private String[] getArguments(XsdOption option, String outputDir) throws MojoExecutionException {
        List<URL> newCp = new ArrayList<URL>();
        List<String> list = new ArrayList<String>();
        if (extensions != null && extensions.size() > 0) {
            Set<Artifact> artifacts = new HashSet<Artifact>();
            
            try {
                for (String ext : extensions) {
                    String[] s = ext.split(":");
                    
                    
                    Artifact artifact = artifactFactory.createBuildArtifact(s[0], s[1], s[2], "jar");
                    artifacts.add(artifact);
                    MavenProject p = mavenProjectBuilder
                        .buildFromRepository(artifact, remoteArtifactRepositories, localRepository);
                    Set a2 = p.createArtifacts(artifactFactory, Artifact.SCOPE_RUNTIME,
                                               new ScopeArtifactFilter(Artifact.SCOPE_RUNTIME));

                    for (Iterator i = a2.iterator(); i.hasNext();) {
                        Artifact a = (Artifact)i.next();
                        artifacts.add(a);
                    }
                }
                for (Artifact art : artifacts) {
                    File f = downloader.download(art.getGroupId(), art.getArtifactId(), art.getVersion(), 
                                                 localRepository, remoteArtifactRepositories);
                    list.add("-classpath");
                    list.add(f.getAbsolutePath());
                    newCp.add(f.toURI().toURL());
                }
            } catch (Exception ex) {
                throw new MojoExecutionException("Could not download extension artifact", ex);
            }
        }
        if (!newCp.isEmpty()) {
            Thread.currentThread()
                .setContextClassLoader(new URLClassLoader(newCp.toArray(new URL[newCp.size()]),
                                                          Thread.currentThread().getContextClassLoader()));
        }
        if (option.getPackagename() != null) {
            list.add("-p");
            list.add(option.getPackagename());
        }
        if (option.getBindingFile() != null) {
            list.add("-b");
            list.add(mapLocation(option.getBindingFile()).toString());
        }
        if (option.getCatalog() != null) {
            list.add("-catalog");
            list.add(option.getCatalog());
        }
        if (option.isExtension()) {
            list.add("-extension");
        }
        if (option.getExtensionArgs() != null) {
            Iterator it = option.getExtensionArgs().iterator();
            while (it.hasNext()) {
                list.add(it.next().toString());
            }
        }          
        if (getLog().isDebugEnabled()) {
            list.add("-verbose");            
        } else { 
            list.add("-quiet");
        }
        list.add("-d");
        list.add(outputDir);
        list.add(mapLocation(option.getXsd()).toString());
       
        return list.toArray(new String[list.size()]);
        
    }
    
    private boolean deleteDir(File f) {
        if (f.isDirectory()) {
            File files[] = f.listFiles();
            for (int idx = 0; idx < files.length; ++idx) {
                deleteDir(files[idx]);
            }
        }
        
        if (f.exists()) {
            return f.delete();
        }
        
        return true;
    }
}
/*******************************************************************************
 * Copyright (c) 2017 BestSolution.at and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Tom Schindl - initial API and implementation
 *******************************************************************************/
package at.bestsolution.maven.osgi.exec;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.shared.utils.cli.CommandLineException;
import org.apache.maven.shared.utils.cli.CommandLineUtils;
import org.codehaus.plexus.logging.Logger;

@Mojo(name="exec-osgi-java", requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class MVNJavaOSGiLaunch extends MVNBaseOSGiLaunchPlugin {

	private static final String EQUINOX_LAUNCHER_MAIN_CLASS = "org.eclipse.equinox.launcher.Main";

	@Parameter(property = "exec.args")
	private String commandlineArgs;

	public void execute() throws MojoExecutionException, MojoFailureException {
		Set<Path> extensionPaths = new HashSet<>();
		Path ini = generateConfigIni(project, extensionPaths);
		
		Optional<URL> launcherJar = project.getArtifacts().stream()
				.filter(a -> "org.eclipse.equinox.launcher".equals(a.getArtifactId())).findFirst()
				.map(a -> {
					try {
						return a.getFile().toURL();
					} catch (MalformedURLException e) {
						throw new RuntimeException(e);
					}
				});
		
		URLClassLoader l = new URLClassLoader( new URL[] { launcherJar.get() } );
		
		List<String> cmd = new ArrayList<>();
		cmd.add("-configuration");
		cmd.add("file:" + ini.toString());
		cmd.addAll(programArguments);

		appendCommandLineArgumentsTo(cmd);
		
		if( vmProperties.containsKey(OSGI_FRAMEWORK_EXTENSIONS) ) {
			String extensionClasspath = extensionPaths.stream().map(Path::toString).collect(Collectors.joining(",","file:",""));
			if( ! extensionClasspath.trim().isEmpty() ) {
				vmProperties.put("osgi.frameworkClassPath",".," + extensionClasspath);
			}
		}

		System.getProperties().putAll(vmProperties);
		
		Thread t = new Thread() {
			public void run() {
				try {
					Class<?> cl = getContextClassLoader().loadClass(EQUINOX_LAUNCHER_MAIN_CLASS);
					Method m = cl.getDeclaredMethod("main", String[].class);
					m.invoke(null, new Object[] {cmd.toArray(new String[0])});

				} catch (ClassNotFoundException | NoSuchMethodException | SecurityException | IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
					logger.error("Can not invoke main-method for " + EQUINOX_LAUNCHER_MAIN_CLASS, e);
				}
			}
		};
		t.setContextClassLoader(l);
		t.start();
		try {
			t.join();
		} catch (InterruptedException e) {
			logger.error("Error on waiting for Equinox launcher to finish.", e);
		}
	}

	private void appendCommandLineArgumentsTo(List<String> cmds) throws MojoExecutionException {
		if (commandlineArgs != null) {
			try {
				cmds.addAll(Arrays.asList(CommandLineUtils.translateCommandline(commandlineArgs)));
			} catch (CommandLineException e) {
				throw new MojoExecutionException(e.getMessage());
			}
		}
	}
}

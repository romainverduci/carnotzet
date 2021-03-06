package com.github.swissquote.carnotzet.core.maven;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.apache.maven.shared.invoker.DefaultInvocationRequest;
import org.apache.maven.shared.invoker.DefaultInvoker;
import org.apache.maven.shared.invoker.InvocationOutputHandler;
import org.apache.maven.shared.invoker.InvocationRequest;
import org.apache.maven.shared.invoker.Invoker;
import org.apache.maven.shared.invoker.MavenInvocationException;

import com.github.swissquote.carnotzet.core.CarnotzetDefinitionException;
import com.github.swissquote.carnotzet.core.CarnotzetModule;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class MavenDependencyResolver {

	private final Function<CarnotzetModuleCoordinates, String> moduleNameProvider;

	private final Path resourcesPath;

	private final Invoker maven = new DefaultInvoker();

	private Path localRepoPath;

	private final TopologicalSorter topologicalSorter = new TopologicalSorter();

	public List<CarnotzetModule> resolve(CarnotzetModuleCoordinates topLevelModuleId) {
		log.debug("Resolving module dependencies");
		Path pomFile = getPomFile(topLevelModuleId);
		Node tree = resolveDependencyTree(pomFile);
		log.debug("Computing topological ordering of GAs in full dependency tree before resolution (maven2)");
		List<Node> topology = topologicalSorter.sort(tree);
		topology = filterInterestingNodes(topology);
		String topLevelModuleName = moduleNameProvider.apply(topLevelModuleId);
		List<CarnotzetModule> result = convertNodesToModules(topology, topLevelModuleName);
		ensureJarFilesAreDownloaded(result, topLevelModuleId);
		return result;
	}

	private List<Node> filterInterestingNodes(List<Node> topology) {
		return topology.stream()
				.filter(n -> n.getScope() == null || "compile".equals(n.getScope()) || "runtime".equals(n.getScope()))
				.collect(Collectors.toList());
	}

	private void ensureJarFilesAreDownloaded(List<CarnotzetModule> result, CarnotzetModuleCoordinates topLevelModuleId) {
		for (CarnotzetModule module : result) {
			if (!module.getJarPath().toFile().exists()) {
				downloadJars(topLevelModuleId);
				if (!module.getJarPath().toFile().exists()) {
					throw new CarnotzetDefinitionException("Unable to find jar [" + module.getJarPath() + "]");
				}
			}
		}
	}

	private void downloadJars(CarnotzetModuleCoordinates topLevelModuleId) {
		String gav = topLevelModuleId.getGroupId() + ":" + topLevelModuleId.getArtifactId() + ":" + topLevelModuleId.getVersion();
		executeMavenBuild(Arrays.asList("org.apache.maven.plugins:maven-dependency-plugin:2.10:get -Dartifact=" + gav), null);
	}

	private List<CarnotzetModule> convertNodesToModules(List<Node> nodes, String topLevelModuleName) {
		List<CarnotzetModule> result = new ArrayList<>();

		for (Node artifact : nodes) {
			CarnotzetModuleCoordinates coord = new CarnotzetModuleCoordinates(
					artifact.getGroupId(),
					artifact.getArtifactId(),
					artifact.getVersion());
			String name = moduleNameProvider.apply(coord);
			if (name == null) {
				continue;
			}
			CarnotzetModule module = CarnotzetModule.builder()
					.id(coord)
					.name(name)
					.topLevelModuleName(topLevelModuleName)
					.jarPath(getJarFile(coord))
					.build();
			result.add(module);
		}

		return result;

	}

	private Path getJarFile(CarnotzetModuleCoordinates artifact) {
		Path localRepoPath = getLocalRepoPath();
		return localRepoPath
				.resolve(artifact.getGroupId().replace(".", "/"))
				.resolve(artifact.getArtifactId())
				.resolve(artifact.getVersion())
				.resolve(artifact.getArtifactId() + "-" + artifact.getVersion() + ".jar");
	}

	private Path getPomFile(CarnotzetModuleCoordinates artifact) {
		Path localRepoPath = getLocalRepoPath();

		Path localFile = localRepoPath
				.resolve(artifact.getGroupId().replace(".", "/"))
				.resolve(artifact.getArtifactId())
				.resolve(artifact.getVersion())
				.resolve(artifact.getArtifactId() + "-" + artifact.getVersion() + ".pom");
		if (!localFile.toFile().exists()) {
			log.debug("pom file [{}] not found. invoking maven dependency:get to download it", localFile);
			String gav = artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion();
			executeMavenBuild(Arrays.asList(
					"org.apache.maven.plugins:maven-dependency-plugin:2.10:get "
							+ "-Dpackaging=pom -Dtransitive=false -Dartifact=" + gav), null);
		}

		log.debug("Using pom file [{}] as top level artifact.", localFile);

		return localFile;

	}

	private void executeMavenBuild(List<String> goals, InvocationOutputHandler outputHandler) {
		log.debug("Invoking maven with goals {}", goals);
		InvocationRequest request = new DefaultInvocationRequest();
		request.setBatchMode(true);
		request.setGoals(goals);
		InvocationOutputHandler outHandler = outputHandler;
		if (outHandler == null) {
			outHandler = log::debug;
		}
		request.setOutputHandler(outHandler);
		try {
			maven.execute(request);
		}
		catch (MavenInvocationException e) {
			throw new CarnotzetDefinitionException("Error invoking mvn " + goals, e);
		}
	}

	/**
	 * Relies on mvn dependency:tree -Dverbose to get a full dependency tree, including omitted ones.
	 * This uses maven 2 and may be inconsistent with maven 3 resolved dependencies.
	 */
	private Node resolveDependencyTree(Path pomFile) {
		Path treePath = resourcesPath.resolve("tree.txt");
		String command = "org.apache.maven.plugins:maven-dependency-plugin:2.10:tree -Dverbose"
				+ " -f " + pomFile.toAbsolutePath().toString()
				+ " -DoutputType=text "
				+ " -DoutputFile=" + treePath.toAbsolutePath().toString();
		executeMavenBuild(Arrays.asList(command), null);
		try {
			return new TreeTextParser().parse(new InputStreamReader(Files.newInputStream(treePath), "UTF-8"));
		}
		catch (ParseException | IOException e) {
			throw new RuntimeException(e);
		}
	}

	private Path getLocalRepoPath() {
		if (this.localRepoPath == null || !this.localRepoPath.toFile().exists()) {
			readUserConfigCache();
			if (this.localRepoPath == null || !this.localRepoPath.toFile().exists()) {
				getLocalRepoLocationFromMaven();
				if (this.localRepoPath == null || !this.localRepoPath.toFile().exists()) {
					throw new CarnotzetDefinitionException("Could not locate maven local repository path");
				}
				writeDotConfigCache();
			}

		}
		return this.localRepoPath;
	}

	private void writeDotConfigCache() {
		Path userConfigFolder = getUserConfigFolder();
		if (!userConfigFolder.toFile().exists()) {
			if (!userConfigFolder.toFile().mkdirs()) {
				throw new CarnotzetDefinitionException("Could not create directory [" + userConfigFolder + "]");
			}
		}
		Path localRepoPathCache = userConfigFolder.resolve("m2LocalRepoPath");
		try {
			FileUtils.writeStringToFile(localRepoPathCache.toFile(), this.localRepoPath.toString(), "UTF-8");
		}
		catch (IOException e) {
			log.warn("Could not write file [{}]", localRepoPathCache);
		}
	}

	private void getLocalRepoLocationFromMaven() {
		LocalRepoLocationOutputHandler handler = new LocalRepoLocationOutputHandler();
		executeMavenBuild(Arrays.asList("help:evaluate -Dexpression=settings.localRepository"), handler);
		this.localRepoPath = Paths.get(handler.getResult());
	}

	private void readUserConfigCache() {
		Path localRepoPathCache = getUserConfigFolder().resolve("m2LocalRepoPath");
		if (localRepoPathCache.toFile().exists()) {
			try {
				this.localRepoPath = Paths.get(FileUtils.readFileToString(localRepoPathCache.toFile()));
			}
			catch (IOException e) {
				log.warn("unable to read file [{}]", localRepoPathCache);
			}
		}
	}

	private Path getUserConfigFolder() {
		return Paths.get(System.getProperty("user.home")).resolve(".carnotzet");
	}

	private static final class LocalRepoLocationOutputHandler implements InvocationOutputHandler {
		@Getter
		private String result;

		@Override
		public void consumeLine(String line) {
			if (!line.contains("INFO")) {
				result = line;
			}
		}
	}

}
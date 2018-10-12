package csw.config.client.javadsl;

import akka.actor.ActorSystem;
import akka.actor.CoordinatedShutdown;
import akka.stream.Materializer;
import csw.location.server.http.JHTTPLocationService;
import csw.config.api.exceptions.FileAlreadyExists;
import csw.config.api.exceptions.FileNotFound;
import csw.config.api.javadsl.IConfigService;
import csw.config.api.javadsl.JFileType;
import csw.config.api.models.*;
import csw.config.client.internal.ActorRuntime;
import csw.config.server.ServerWiring;
import csw.config.server.commons.TestFileUtils;
import csw.config.server.http.HttpService;
import csw.location.api.javadsl.ILocationService;
import csw.location.client.javadsl.JHttpLocationServiceFactory;
import org.junit.*;
import org.junit.rules.ExpectedException;
import scala.concurrent.Await;
import scala.concurrent.duration.Duration;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.hamcrest.CoreMatchers.isA;

// DEOPSCSW-138: Split Config API into Admin API and Client API
// DEOPSCSW-103: Java API for Configuration service
public class JConfigAdminApiTest  {
    private static JHTTPLocationService jHttpLocationService;
    private static ActorRuntime actorRuntime = new ActorRuntime(ActorSystem.create());
    private static ILocationService clientLocationService = JHttpLocationServiceFactory.makeLocalClient(actorRuntime.actorSystem(), actorRuntime.mat());
    private static IConfigService configService = JConfigClientFactory.adminApi(actorRuntime.actorSystem(), clientLocationService);

    private static ServerWiring serverWiring = new ServerWiring();
    private static HttpService httpService = serverWiring.httpService();
    private TestFileUtils testFileUtils = new TestFileUtils(serverWiring.settings());

    private Materializer mat = actorRuntime.mat();

    private String configValue1 = "axisName1 = tromboneAxis\naxisName2 = tromboneAxis2\naxisName3 = tromboneAxis3";
    private String configValue2 = "axisName11 = tromboneAxis\naxisName22 = tromboneAxis2\naxisName3 = tromboneAxis33";
    private String configValue3 = "axisName111 = tromboneAxis\naxisName222 = tromboneAxis2\naxisName3 = tromboneAxis333";
    private String configValue4 = "axisName1111 = tromboneAxis\naxisName2222 = tromboneAxis2\naxisName3 = tromboneAxis3333";
    private String configValue5 = "axisName11111 = tromboneAxis\naxisName22222 = tromboneAxis2\naxisName3 = tromboneAxis3333";

    @Rule
    public final ExpectedException exception = ExpectedException.none();

    @BeforeClass
    public static void beforeAll() throws Exception {
        jHttpLocationService = new JHTTPLocationService();
        Await.result(httpService.registeredLazyBinding(), Duration.create(20, "seconds"));
    }

    @Before
    public void initSvnRepo() {
        serverWiring.svnRepo().initSvnRepo();
    }

    @After
    public void deleteServerFiles() {
        testFileUtils.deleteServerFiles();
    }

    @AfterClass
    public static void afterAll() throws Exception {
        Await.result(httpService.shutdown(CoordinatedShutdown.unknownReason()), Duration.create(20, "seconds"));
        Await.result(actorRuntime.actorSystem().terminate(), Duration.create(20, "seconds"));
        Await.result(serverWiring.actorSystem().terminate(), Duration.create(20, "seconds"));
        jHttpLocationService.afterAll();
    }

    // DEOPSCSW-42: Storing text based component configuration
    // DEOPSCSW-48: Store new configuration file in Config. service
    @Test
    public void testCreateAndRetrieveFile() throws ExecutionException, InterruptedException {
        Path path = Paths.get("/tmt/trombone/assembly/conf/normalfiles/test/test.conf");
        configService.create(path, ConfigData.fromString(configValue1), false, "commit test file").get();
        Optional<ConfigData> configData = configService.getLatest(path).get();
        Assert.assertEquals(configData.get().toJStringF(mat).get(), configValue1);
    }

    // DEOPSCSW-42: Storing text based component configuration
    // DEOPSCSW-48: Store new configuration file in Config. service
    @Test
    public void testFileAlreadyExistsExceptionOnCreate() throws ExecutionException, InterruptedException {
        Path path = Paths.get("/tmt/trombone/assembly/conf/normalfiles/test/test.conf");
        configService.create(path, ConfigData.fromString(configValue1), false, "commit test file").get();
        exception.expectCause(isA(FileAlreadyExists.class));
        configService.create(path, ConfigData.fromString(configValue1), false, "commit test file").get();
    }

    @Test
    public void testCreateFileInAnnexStore() throws ExecutionException, InterruptedException {
        Path path = Paths.get("SomeAnnexFile.txt");
        configService.create(path, ConfigData.fromString(configValue1), true, "creating file for annex store").get();
        Optional<ConfigData> configData = configService.getLatest(path).get();
        Assert.assertEquals(configData.get().toJStringF(mat).get(), configValue1);
    }

    // DEOPSCSW-49: Update an Existing File with a New Version
    @Test
    public void testUpdateExistingFile() throws ExecutionException, InterruptedException {
        Path path = Paths.get("/assembly.conf");
        configService.create(path, ConfigData.fromString(configValue1), false, "commit assembly conf").get();
        Optional<ConfigData> configData = configService.getLatest(path).get();
        Assert.assertEquals(configData.get().toJStringF(mat).get(), configValue1);

        configService.update(path, ConfigData.fromString(configValue2), "commit updated assembly conf").get();
        Optional<ConfigData> configDataUpdated = configService.getLatest(path).get();
        Assert.assertEquals(configDataUpdated.get().toJStringF(mat).get(), configValue2);
    }

    // DEOPSCSW-49: Update an Existing File with a New Version
    @Test
    public void testUpdateReturnsFileNotFoundExceptionOnAbsenceOfFile() throws ExecutionException, InterruptedException {
        Path path = Paths.get("/tmt/trombone/assembly.conf");
        exception.expectCause(isA(FileNotFound.class));
        configService.update(path, ConfigData.fromString(configValue1), "commit assembly conf").get();
    }

    // DEOPSCSW-70: Retrieve the current/most recent version of an existing configuration file
    // DEOPSCSW-71: Retrieve any version of a configuration file using its unique id
    @Test
    public void testGetReturnsNoneIfFileNotExists() throws ExecutionException, InterruptedException {
        Path path = Paths.get("/tmt/text/file/not/exist/app.conf");
        Assert.assertEquals(configService.getLatest(path).get(), Optional.empty());
    }

    // DEOPSCSW-46: Unique identifier for configuration file version
    @Test
    public void  testEachRevisionHasUniqueId() throws ExecutionException, InterruptedException {
        Path tromboneHcdConf       = Paths.get("trombone/test/hcd/akka/hcd.conf");
        Path tromboneAssemblyConf  = Paths.get("trombone/test/assembly/akka/assembly.conf");
        Path tromboneContainerConf = Paths.get("trombone/test/container/akka/container.conf");
        Path redisConf             = Paths.get("redis/test/text/redis.conf");

        ConfigId configId1 = configService.create(tromboneHcdConf, ConfigData.fromString(configValue1), "creating tromboneHCD.conf").get();
        ConfigId configId2 = configService.create(tromboneAssemblyConf, ConfigData.fromString(configValue2), "creating tromboneAssembly.conf").get();
        ConfigId configId3 = configService.create(redisConf, ConfigData.fromString(configValue3), "creating binary conf file").get();
        ConfigId configId4 = configService.create(tromboneContainerConf, ConfigData.fromString(configValue4), "creating trombone container.conf").get();
        ConfigId configId5 = configService.update(tromboneHcdConf, ConfigData.fromString(configValue5), "updating tromboneHCD.conf").get();
        ConfigId configId6 = configService.update(tromboneAssemblyConf, ConfigData.fromString(configValue2), "updating tromboneAssembly.conf").get();

        ConfigData configData1 = configService.getById(tromboneHcdConf, configId1).get().get();
        Assert.assertEquals(configData1.toJStringF(mat).get(), configValue1);

        ConfigData configData2 = configService.getById(tromboneAssemblyConf, configId2).get().get();
        Assert.assertEquals(configData2.toJStringF(mat).get(), configValue2);

        ConfigData configData3 = configService.getById(redisConf, configId3).get().get();
        Assert.assertEquals(configData3.toJStringF(mat).get(), configValue3);

        ConfigData configData4 = configService.getById(tromboneContainerConf, configId4).get().get();
        Assert.assertEquals(configData4.toJStringF(mat).get(), configValue4);

        ConfigData configData5 = configService.getById(tromboneHcdConf, configId5).get().get();
        Assert.assertEquals(configData5.toJStringF(mat).get(), configValue5);

        ConfigData configData6 = configService.getById(tromboneAssemblyConf, configId6).get().get();
        Assert.assertEquals(configData6.toJStringF(mat).get(), configValue2);
    }

    // DEOPSCSW-70: Retrieve the current/most recent version of an existing configuration file
    // DEOPSCSW-71: Retrieve any version of a configuration file using its unique id
    @Test
    public void testSpecificVersionRetrieval() throws ExecutionException, InterruptedException {
        Path path = Paths.get("/a/b/csw.conf");
        configService.create(path, ConfigData.fromString(configValue1), false, "commit csw conf path").get();
        Assert.assertEquals(configService.getLatest(path).get().get().toJStringF(mat).get(), configValue1);

        ConfigId configId = configService.update(path, ConfigData.fromString(configValue2), "commit updated conf path").get();

        configService.update(path, ConfigData.fromString(configValue3), "updated config to assembly").get();
        Assert.assertEquals(configService.getLatest(path).get().get().toJStringF(mat).get(), configValue3);

        Assert.assertEquals(configService.getById(path, configId).get().get().toJStringF(mat).get(), configValue2);
    }

    @Test
    public void testRetrieveVersionBasedOnDate() throws ExecutionException, InterruptedException {
        Path path = Paths.get("/test.conf");
        configService.create(path, ConfigData.fromString(configValue1), false, "commit initial configuration").get();
        Assert.assertEquals(configService.getLatest(path).get().get().toJStringF(mat).get(), configValue1);

        configService.update(path, ConfigData.fromString(configValue2), "updated config to assembly").get();
        Instant instant = Instant.now();
        configService.update(path, ConfigData.fromString(configValue3), "updated config to assembly").get();

        Assert.assertEquals(configService.getLatest(path).get().get().toJStringF(mat).get(), configValue3);
        Assert.assertEquals(configService.getByTime(path, instant).get().get().toJStringF(mat).get(), configValue2);
    }

    // DEOPSCSW-45: Saving version information for config. file
    // DEOPSCSW-76: Access a list of all the versions of a stored configuration file
    // DEOPSCSW-63: Add comment while creating or updating a configuration file
    // DEOPSCSW-83: Retrieve file based on range of time for being most recent version
    @Test
    public void testHistoryOfAFile() throws ExecutionException, InterruptedException {
        Path path = Paths.get("/test.conf");

        try{
            exception.expectCause(isA(FileNotFound.class));
            configService.history(path).get();
        }finally {
            String comment1 = "commit version 1";
            String comment2 = "commit version 2";
            String comment3 = "commit version 3";

            ConfigId configId1 = configService.create(path, ConfigData.fromString(configValue1), false, comment1).get();
            Instant createTS = Instant.now();

            ConfigId configId2 = configService.update(path, ConfigData.fromString(configValue2), comment2).get();
            ConfigId configId3 = configService.update(path, ConfigData.fromString(configValue3), comment3).get();
            Instant updateTS = Instant.now();

            List<ConfigFileRevision> historyByPath = configService.history(path).get();

            Assert.assertEquals(historyByPath.size(), 3);
            Assert.assertEquals(historyByPath.stream().map(ConfigFileRevision::id).collect(Collectors.toList()),
                    new ArrayList<>(Arrays.asList(configId3, configId2, configId1)));
            Assert.assertEquals(historyByPath.stream().map(ConfigFileRevision::comment).collect(Collectors.toList()),
                    new ArrayList<>(Arrays.asList(comment3, comment2, comment1)));

            List<ConfigFileRevision> historyByPathAndSize = configService.history(path, 2).get();

            Assert.assertEquals(historyByPathAndSize.size(), 2);
            Assert.assertEquals(historyByPathAndSize.stream().map(ConfigFileRevision::id).collect(Collectors.toList()),
                    new ArrayList<>(Arrays.asList(configId3, configId2)));
            Assert.assertEquals(historyByPathAndSize.stream().map(ConfigFileRevision::comment).collect(Collectors.toList()),
                    new ArrayList<>(Arrays.asList(comment3, comment2)));

            Assert.assertEquals(configService.history(path, createTS, updateTS).get().stream().map(ConfigFileRevision::id).collect(Collectors.toList()),
                    new ArrayList<>(Arrays.asList(configId3, configId2)));

            Assert.assertEquals(configService.historyFrom(path, createTS).get().stream().map(ConfigFileRevision::id).collect(Collectors.toList()),
                    new ArrayList<>(Arrays.asList(configId3, configId2)));

            Assert.assertEquals(configService.historyFrom(path, createTS, 1).get().stream().map(ConfigFileRevision::id).collect(Collectors.toList()),
                    new ArrayList<>(Arrays.asList(configId3)));

            Assert.assertEquals(configService.historyUpTo(path, updateTS).get().stream().map(ConfigFileRevision::id).collect(Collectors.toList()),
                    new ArrayList<>(Arrays.asList(configId3, configId2, configId1)));

            Assert.assertEquals(configService.historyUpTo(path, updateTS, 2).get().stream().map(ConfigFileRevision::id).collect(Collectors.toList()),
                    new ArrayList<>(Arrays.asList(configId3, configId2)));
        }
    }

    // DEOPSCSW-48: Store new configuration file in Config. service
    @Test
    public void testListAllFiles() throws ExecutionException, InterruptedException {
        Path tromboneConfig = Paths.get("trombone.conf");
        Path assemblyConfig = Paths.get("a/b/assembly/assembly.conf");

        String tromboneConfigComment = "hello trombone";
        String assemblyConfigComment = "hello assembly";

        ConfigId tromboneConfigId = configService.create(tromboneConfig, ConfigData.fromString("axisName = tromboneAxis"), false, tromboneConfigComment).get();
        ConfigId assemblyConfigId = configService.create(assemblyConfig, ConfigData.fromString("assemblyHCDCount = 3"), false, assemblyConfigComment).get();

        ConfigFileInfo tromboneConfigInfo = new ConfigFileInfo(tromboneConfig, tromboneConfigId, tromboneConfigComment);
        ConfigFileInfo assemblyConfigInfo = new ConfigFileInfo(assemblyConfig, assemblyConfigId, assemblyConfigComment);

        Assert.assertEquals(configService.list().get(), new ArrayList<>(Arrays.asList(assemblyConfigInfo, tromboneConfigInfo)));
    }

    // DEOPSCSW-74: Check config file existence by unique name
    @Test
    public void testExists() throws ExecutionException, InterruptedException {
        Path path = Paths.get("/test.conf");
        Assert.assertFalse(configService.exists(path).get());

        Path path1 = Paths.get("a/test.csw.conf");
        configService.create(path1, ConfigData.fromString(configValue1), false, "commit config file").get();

        Assert.assertTrue(configService.exists(path1).get());
    }

    // DEOPSCSW-91: Delete a given path
    @Test
    public void testDelete() throws ExecutionException, InterruptedException {
        Path path = Paths.get("tromboneHCD.conf");
        configService.create(path, ConfigData.fromString(configValue1), false, "commit trombone config file").get();

        Assert.assertEquals(configService.getLatest(path).get().get().toJStringF(mat).get(), configValue1);

        configService.delete(path, "no longer needed").get();
        Assert.assertEquals(configService.getLatest(path).get(), Optional.empty());
    }

    // DEOPSCSW-77: Set default version of configuration file in config. service
    // DEOPSCSW-78: Get the default version of a configuration file
    // DEOPSCSW-79: Reset the default version of a configuration file
    // DEOPSCSW-70: Retrieve the current/most recent version of an existing configuration file
    // DEOPSCSW-139: Provide new routes to get/set the current active version of the file
    @Test
    public void testGetSetAndResetActiveConfigFile() throws ExecutionException, InterruptedException {
        Path path = Paths.get("/tmt/text-files/trombone_hcd/application.conf");
        configService.create(path, ConfigData.fromString(configValue1), false, "hello world").get();
        Assert.assertEquals(configService.getLatest(path).get().get().toJStringF(mat).get(), configValue1);

        ConfigId configIdUpdate1 = configService.update(path, ConfigData.fromString(configValue2), "Updated config to assembly").get();
        configService.update(path, ConfigData.fromString(configValue3), "Updated config to assembly").get();
        Assert.assertEquals(configService.getActive(path).get().get().toJStringF(mat).get(), configValue1);

        configService.setActiveVersion(path, configIdUpdate1, "setting active version").get();
        Assert.assertEquals(configService.getActive(path).get().get().toJStringF(mat).get(), configValue2);

        configService.resetActiveVersion(path, "resetting active version of file").get();
        Assert.assertEquals(configService.getActive(path).get().get().toJStringF(mat).get(), configValue3);

        configService.resetActiveVersion(path, "resetting active version").get();
        Assert.assertEquals(configService.getActive(path).get().get().toJStringF(mat).get(), configValue3);
    }

    //DEOPSCSW-86: Retrieve a version of a configuration file based on time range for being default version
    @Test
    public void testGetHistoryOfActiveVersionsOfFile() throws ExecutionException, InterruptedException {
        String createActiveComment = "initializing active file with the first version";
        // create file
        Path file      = Paths.get("/tmt/test/setactive/getactive/resetactive/active.conf");
        ConfigId configId1 = configService.create(file, ConfigData.fromString(configValue1),  false, "create").get();
        Instant createActiveTS = Instant.now();

        // update file twice
        ConfigId configId3 = configService.update(file, ConfigData.fromString(configValue3), "Update 1").get();
        ConfigId configId4 = configService.update(file, ConfigData.fromString(configValue4), "Update 2").get();

        // set active version of file to id=3
        String setActiveComment = "Setting active version for the first time";
        configService.setActiveVersion(file, configId3, setActiveComment).get();
        Instant setActiveTS = Instant.now();

        // reset active version and check that get active returns latest version
        String resetActiveComment = "resetting active version";
        configService.resetActiveVersion(file, resetActiveComment).get();
        Instant resetActiveTS = Instant.now();

        // update file and check get active returns last active version and not the latest version
        configService.update(file, ConfigData.fromString(configValue5), "Update 3").get();
        Assert.assertEquals(configService.getActiveVersion(file).get().get(), configId4);

        // verify complete active file history without any parameter
        List<ConfigFileRevision> completeHistory = configService.historyActive(file).get();
        Assert.assertEquals(completeHistory.size(), 3);
        Assert.assertEquals(completeHistory.stream().map(ConfigFileRevision::id).collect(Collectors.toList()), Arrays.asList(configId4, configId3, configId1));
        Assert.assertEquals(completeHistory.stream().map(ConfigFileRevision::comment).collect(Collectors.toList()), Arrays.asList(resetActiveComment, setActiveComment, createActiveComment));

        // verify active file history with max results parameter
        Assert.assertEquals(configService.historyActive(file, 2).get().size(), 2);

        // verify active file history from given timestamp
        List<ConfigFileRevision> historyFrom = configService.historyActiveFrom(file, createActiveTS).get();
        Assert.assertEquals(historyFrom.stream().map(ConfigFileRevision::id).collect(Collectors.toList()), Arrays.asList(configId4, configId3));
        Assert.assertEquals(historyFrom.stream().map(ConfigFileRevision::comment).collect(Collectors.toList()), Arrays.asList(resetActiveComment, setActiveComment));

        // verify active file history till given timestamp
        List<ConfigFileRevision> historyUpTo = configService.historyActiveUpTo(file, setActiveTS).get();
        Assert.assertEquals(historyUpTo.stream().map(ConfigFileRevision::id).collect(Collectors.toList()), Arrays.asList(configId3, configId1));
        Assert.assertEquals(historyUpTo.stream().map(ConfigFileRevision::comment).collect(Collectors.toList()), Arrays.asList(setActiveComment, createActiveComment));

        // verify active file history within the range of given timestamps
        List<ConfigFileRevision> historyWithin = configService.historyActive(file, createActiveTS, resetActiveTS).get();
        Assert.assertEquals(historyWithin.stream().map(ConfigFileRevision::id).collect(Collectors.toList()), Arrays.asList(configId4, configId3));
        Assert.assertEquals(historyWithin.stream().map(ConfigFileRevision::comment).collect(Collectors.toList()), Arrays.asList(resetActiveComment, setActiveComment));

    }

    // DEOPSCSW-78: Get the default version of a configuration file
    // DEOPSCSW-70: Retrieve the current/most recent version of an existing configuration file
    // DEOPSCSW-139: Provide new routes to get/set the current active version of the file
    @Test
    public void testGetActiveReturnsNoneIfFileNotExist() throws ExecutionException, InterruptedException {
        Path path = Paths.get("/tmt/text-files/trombone_hcd/application.conf");
        Assert.assertEquals(configService.getActive(path).get(), Optional.empty());
    }

    @Test
    public void testListFilesFromAnnexStoreWithoutShaSuffix() throws ExecutionException, InterruptedException {
        Path tromboneConfig = Paths.get("trombone.conf");
        Path assemblyConfig = Paths.get("a/b/assembly/assembly.conf");

        String tromboneConfigComment = "test{Annex file no1}";
        String assemblyConfigComment = "test{Annex file no2}";

        ConfigId tromboneConfigId = configService.create(tromboneConfig, ConfigData.fromString("axisName = tromboneAxis"),
                                                        true,
                                                         tromboneConfigComment).get();
        ConfigId assemblyConfigId = configService.create(assemblyConfig, ConfigData.fromString("assemblyHCDCount = 3"),
                                                        true,
                                                         assemblyConfigComment).get();

        ConfigFileInfo tromboneConfigInfo = new ConfigFileInfo(tromboneConfig, tromboneConfigId, tromboneConfigComment);
        ConfigFileInfo assemblyConfigInfo = new ConfigFileInfo(assemblyConfig, assemblyConfigId, assemblyConfigComment);

        Assert.assertEquals(configService.list().get(), new ArrayList<>(Arrays.asList(assemblyConfigInfo, tromboneConfigInfo)));
    }

    // DEOPSCSW-74: Check config file existence by unique name
    @Test
    public void testFileExistsInAnnexStore() throws ExecutionException, InterruptedException {
        Path path = Paths.get("/test.conf");
        Assert.assertFalse(configService.exists(path).get());

        Path newPath = Paths.get("a/test.csw.conf");
        configService.create(newPath, ConfigData.fromString(configValue3), true, "create annex file").get();

        Assert.assertTrue(configService.exists(newPath).get());
    }

    // DEOPSCSW-49: Update an Existing File with a New Version
    // DEOPSCSW-83: Retrieve file based on range of time for being most recent version
    @Test
    public void testUpdateAndHistoryOfFilesInAnnexStore() throws ExecutionException, InterruptedException {
        Path path = Paths.get("/tmt/binary-files/trombone_hcd/app.bin");
        ConfigId configIdCreate = configService.create(path, ConfigData.fromString(configValue1), true, "commit initial configuration").get();
        Instant createTS = Instant.now();
        Assert.assertEquals(configService.getLatest(path).get().get().toJStringF(mat).get(), configValue1);

        ConfigId configIdUpdate1 = configService.update(path, ConfigData.fromString(configValue2), "updated config to assembly").get();
        ConfigId configIdUpdate2 = configService.update(path, ConfigData.fromString(configValue3), "updated config to assembly").get();
        Instant updateTS = Instant.now();

        Assert.assertEquals(configService.history(path).get().size(), 3);
        Assert.assertEquals(configService.history(path).get().stream().map(ConfigFileRevision::id).collect(Collectors.toList()),
                new ArrayList<>(Arrays.asList(configIdUpdate2, configIdUpdate1, configIdCreate)));

        Assert.assertEquals(configService.history(path, 2).get().size(), 2);
        Assert.assertEquals(configService.history(path, 2).get().stream().map(ConfigFileRevision::id).collect(Collectors.toList()),
                new ArrayList<>(Arrays.asList(configIdUpdate2, configIdUpdate1)));

        Assert.assertEquals(configService.history(path, createTS, updateTS).get().stream().map(ConfigFileRevision::id).collect(Collectors.toList()),
                new ArrayList<>(Arrays.asList(configIdUpdate2, configIdUpdate1)));

        Assert.assertEquals(configService.historyFrom(path, createTS).get().stream().map(ConfigFileRevision::id).collect(Collectors.toList()),
                new ArrayList<>(Arrays.asList(configIdUpdate2, configIdUpdate1)));

        Assert.assertEquals(configService.historyUpTo(path, updateTS).get().stream().map(ConfigFileRevision::id).collect(Collectors.toList()),
                new ArrayList<>(Arrays.asList(configIdUpdate2, configIdUpdate1, configIdCreate)));

        Assert.assertEquals(configService.historyUpTo(path, updateTS, 2).get().stream().map(ConfigFileRevision::id).collect(Collectors.toList()),
                new ArrayList<>(Arrays.asList(configIdUpdate2, configIdUpdate1)));

    }

    // DEOPSCSW-77: Set default version of configuration file in config. service
    // DEOPSCSW-78: Get the default version of a configuration file
    // DEOPSCSW-70: Retrieve the current/most recent version of an existing configuration file
    @Test
    public void testGetAndSetActiveConfigFileFromAnnexStore() throws ExecutionException, InterruptedException {
        Path path = Paths.get("/tmt/binary-files/trombone_hcd/app.bin");
        configService.create(path, ConfigData.fromString(configValue1), true, "some comment").get();
        Assert.assertEquals(configService.getLatest(path).get().get().toJStringF(mat).get(), configValue1);

        ConfigId configIdUpdate1 = configService.update(path, ConfigData.fromString(configValue2), "Updated config to assembly").get();
        configService.update(path, ConfigData.fromString(configValue3), "Updated config").get();

        // check that getActive call before any setActive call should return the file with id with which it was created
        Assert.assertEquals(configService.getActive(path).get().get().toJStringF(mat).get(), configValue1);

        configService.setActiveVersion(path, configIdUpdate1, "setting active version").get();
        Assert.assertEquals(configService.getActive(path).get().get().toJStringF(mat).get(), configValue2);

        configService.resetActiveVersion(path, "resetting active version").get();
        Assert.assertEquals(configService.getActive(path).get().get().toJStringF(mat).get(), configValue3);
    }

    @Test
    public void testRetrieveVersionBasedOnDateForFileInAnnexStore() throws ExecutionException, InterruptedException {
        Path path = Paths.get("/test.conf");
        configService.create(path, ConfigData.fromString(configValue1), true, "commit initial configuration to annex store").get();
        Assert.assertEquals(configService.getLatest(path).get().get().toJStringF(mat).get(), configValue1);

        configService.update(path, ConfigData.fromString(configValue2), "updated config to assembly").get();
        Instant instant = Instant.now();
        configService.update(path, ConfigData.fromString(configValue3), "updated config to assembly").get();

        Assert.assertEquals(configService.getLatest(path).get().get().toJStringF(mat).get(), configValue3);
        Assert.assertEquals(configService.getByTime(path, instant).get().get().toJStringF(mat).get(), configValue2);
    }

    @Test
    public void testActiveFilesAreExcludedInList() throws ExecutionException, InterruptedException {
        Path tromboneConfig = Paths.get("trombone.conf");
        Path assemblyConfig = Paths.get("a/b/assembly/assembly.conf");

        String tromboneConfigComment = "hello trombone";
        String assemblyConfigComment = "hello assembly";

        // Add files to repo
        ConfigId tromboneConfigId = configService
                .create(tromboneConfig, ConfigData.fromString(configValue1),false, tromboneConfigComment)
                .get();
        ConfigId assemblyConfigId = configService
                .create(assemblyConfig, ConfigData.fromString(configValue2),true, assemblyConfigComment)
                .get();

        configService.setActiveVersion(tromboneConfig, tromboneConfigId, "settting active version").get();
        configService.setActiveVersion(assemblyConfig, assemblyConfigId, "settting active version").get();

        // list files from repo and assert that it contains added files

        Set<ConfigFileInfo> actual = new HashSet<ConfigFileInfo>(configService.list().get());

        Set<ConfigFileInfo> expected = new HashSet<>(Arrays.asList(new ConfigFileInfo(tromboneConfig, tromboneConfigId, tromboneConfigComment),
                new ConfigFileInfo(assemblyConfig, assemblyConfigId, assemblyConfigComment)));

        Assert.assertEquals(expected, actual);
    }

    //DEOPSCSW-75 List the names of configuration files that match a path
    @Test
    public void testListIsFilteredBasedOnPattern() throws ExecutionException, InterruptedException {
        Path tromboneConfig = Paths.get("trombone.conf");
        Path assemblyConfig = Paths.get("a/b/assembly/assembly.conf");
        Path hcdConfig = Paths.get("a/b/c/hcd/hcd.conf");

        configService.create(tromboneConfig, ConfigData.fromString(configValue1), false, "hello trombone").get();
        configService.create(assemblyConfig, ConfigData.fromString(configValue2), true, "hello assembly").get();
        configService.create(hcdConfig, ConfigData.fromString(configValue3), false, "hello hcd").get();

        Set<Path> expected = new HashSet<>(Arrays.asList(hcdConfig, assemblyConfig));
        Stream<Path> actualStream = configService.list("a/b/.*").get().stream().map(ConfigFileInfo::path);
        Assert.assertEquals(expected, actualStream.collect(Collectors.toSet()));

        Set<Path> expected1 = Collections.singleton(hcdConfig);
        Stream<Path> actualStream1 = configService.list("a/b/c.*").get().stream().map(ConfigFileInfo::path);
        Assert.assertEquals(expected1, actualStream1.collect(Collectors.toSet()));

        Set<Path> all = new HashSet<>(Arrays.asList(hcdConfig, assemblyConfig, tromboneConfig));
        Stream<Path> actualStream2 = configService.list(".*.conf").get().stream().map(ConfigFileInfo::path);
        Assert.assertEquals(all, actualStream2.collect(Collectors.toSet()));

        List<ConfigFileInfo> fileInfos = configService.list("a/b/c/d.*").get();
        Assert.assertTrue(fileInfos.isEmpty());

        Stream<Path> actualStream3 = configService.list().get().stream().map(ConfigFileInfo::path);
        Assert.assertEquals(all, actualStream3.collect(Collectors.toSet()));

        Stream<Path> actualStream4 = configService.list(".*hcd.*").get().stream().map(ConfigFileInfo::path);
        Assert.assertEquals(expected1, actualStream4.collect(Collectors.toSet()));
    }

    //DEOPSCSW-132 List oversize and normal sized files
    //DEOPSCSW-75 List the names of configuration files that match a path
    @Test
    public void testListIsFilteredBasedOnTypeAndPattern() throws ExecutionException, InterruptedException {
        Path tromboneConfig = Paths.get("trombone.conf");
        Path hcdConfig = Paths.get("a/b/c/hcd/hcd.conf");
        Path assemblyConfig1 = Paths.get("a/b/assembly/assembly1.conf");
        Path assemblyConfig2 = Paths.get("a/b/c/assembly/assembly2.conf");

        configService.create(tromboneConfig, ConfigData.fromString(configValue1), false, "hello trombone").get();
        configService.create(assemblyConfig1, ConfigData.fromString(configValue2), true, "hello assembly1").get();
        configService.create(assemblyConfig2, ConfigData.fromString(configValue2), true, "hello assembly2").get();
        configService.create(hcdConfig, ConfigData.fromString(configValue3), false, "hello hcd").get();

        Set<Path> expected1 = new HashSet<>(Arrays.asList(assemblyConfig1, assemblyConfig2));
        Stream<Path> actualStream1 = configService.list(JFileType.Annex).get().stream().map(ConfigFileInfo::path);
        Assert.assertEquals(expected1, actualStream1.collect(Collectors.toSet()));

        Set<Path> expected2 = new HashSet<>(Arrays.asList(tromboneConfig, hcdConfig));
        Stream<Path> actualStream2 = configService.list(JFileType.Normal).get().stream().map(ConfigFileInfo::path);
        Assert.assertEquals(expected2, actualStream2.collect(Collectors.toSet()));

        Set<Path> expected3 = new HashSet<>(Collections.singleton(assemblyConfig2));
        Stream<Path> actualStream3 = configService.list(JFileType.Annex, "a/b/c.*").get().stream().map(ConfigFileInfo::path);
        Assert.assertEquals(expected3, actualStream3.collect(Collectors.toSet()));

        Stream<Path> actualStream4 = configService.list(JFileType.Annex, ".*.conf").get().stream().map(ConfigFileInfo::path);
        Assert.assertEquals(expected1, actualStream4.collect(Collectors.toSet()));

        Stream<Path> actualStream5 = configService.list(JFileType.Annex, ".*assembly.*").get().stream().map(ConfigFileInfo::path);
        Assert.assertEquals(expected1, actualStream5.collect(Collectors.toSet()));

        Set<Path> expected6 = new HashSet<>(Collections.singleton(hcdConfig));
        Stream<Path> actualStream6 = configService.list(JFileType.Normal, "a/b/c.*").get().stream().map(ConfigFileInfo::path);
        Assert.assertEquals(expected6, actualStream6.collect(Collectors.toSet()));

        Stream<Path> actualStream7 = configService.list(JFileType.Normal, ".*.conf").get().stream().map(ConfigFileInfo::path);
        Assert.assertEquals(expected2, actualStream7.collect(Collectors.toSet()));

        Set<Path> expected8 = new HashSet<>(Collections.singleton(tromboneConfig));
        Stream<Path> actualStream8 = configService.list(JFileType.Normal, ".*trombone.*").get().stream().map(ConfigFileInfo::path);
        Assert.assertEquals(expected8, actualStream8.collect(Collectors.toSet()));
    }

    //DEOPSCSW-140 Provide new routes to get active file as of date
    @Test
    public void testActiveVersionBasedOnTime() throws ExecutionException, InterruptedException {

        // create file
        Path file = Paths.get("/tmt/test/setactive/getactive/resetactive/active.conf");
        configService.create(file, ConfigData.fromString(configValue1), false, "hello world").get();

        // update file twice
        ConfigId configId = configService.update(file, ConfigData.fromString(configValue2), "Updated config to assembly").get();
        configService.update(file, ConfigData.fromString(configValue3), "Updated config to assembly").get();

        Instant tHeadRevision = Instant.now();
        configService.setActiveVersion(file, configId, "Setting active version for the first time").get();

        Instant tActiveRevision1 = Instant.now();

        configService.resetActiveVersion(file, "resetting active version").get();

        Assert.assertEquals(configService.getActiveByTime(file, tHeadRevision).get().get().toJStringF(mat).get(), configValue1);
        Assert.assertEquals(configService.getActiveByTime(file, tActiveRevision1).get().get().toJStringF(mat).get(), configValue2);
        Assert.assertEquals(configService.getActiveByTime(file, Instant.now()).get().get().toJStringF(mat).get(), configValue3);
    }

    //DEOPSCSW-133: Provide meta config for normal and oversize repo
    @Test
    public void testGetMetadata() throws ExecutionException, InterruptedException {
        ConfigMetadata metadata = configService.getMetadata().get();
        Assert.assertNotEquals(metadata.repoPath(), "");
        Assert.assertNotEquals(metadata.annexPath(), "");
        Assert.assertNotEquals(metadata.annexMinFileSize(), "");
        Assert.assertNotEquals(metadata.maxConfigFileSize(), "");
    }
}

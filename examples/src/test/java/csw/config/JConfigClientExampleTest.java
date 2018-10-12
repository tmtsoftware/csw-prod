package csw.config;

import akka.actor.ActorSystem;
import akka.actor.CoordinatedShutdown;
import akka.stream.Materializer;
import csw.location.server.http.JHTTPLocationService;
import csw.config.api.javadsl.IConfigClientService;
import csw.config.api.javadsl.IConfigService;
import csw.config.api.javadsl.JFileType;
import csw.config.api.models.*;
import csw.config.client.internal.ActorRuntime;
import csw.config.client.javadsl.JConfigClientFactory;
import csw.config.server.ServerWiring;
import csw.config.server.commons.TestFileUtils;
import csw.config.server.http.HttpService;
import csw.location.api.javadsl.ILocationService;
import csw.location.client.javadsl.JHttpLocationServiceFactory;
import org.junit.*;
import org.junit.rules.ExpectedException;
import scala.concurrent.Await;
import scala.concurrent.duration.Duration;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

public class JConfigClientExampleTest {
    private static ActorRuntime actorRuntime = new ActorRuntime(ActorSystem.create());
    private static ILocationService clientLocationService = JHttpLocationServiceFactory.makeLocalClient(actorRuntime.actorSystem(), actorRuntime.mat());
    private static JHTTPLocationService jhttpLocationService;

    //#create-api
    //config client API
    IConfigClientService clientApi = JConfigClientFactory.clientApi(actorRuntime.actorSystem(), clientLocationService);
    //config admin API
    IConfigService adminApi = JConfigClientFactory.adminApi(actorRuntime.actorSystem(), clientLocationService);
    //#create-api

    private static ServerWiring serverWiring = ServerWiring.make(clientLocationService.asScala());
    private static HttpService httpService = serverWiring.httpService();
    private TestFileUtils testFileUtils = new TestFileUtils(serverWiring.settings());

    private Materializer mat = actorRuntime.mat();

    //#declare_string_config
    String defaultStrConf = "foo { bar { baz : 1234 } }";
    //#declare_string_config

    @Rule
    public final ExpectedException exception = ExpectedException.none();

    @BeforeClass
    public static void beforeAll() throws Exception {
        jhttpLocationService = new JHTTPLocationService();
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
    }


    @Test
    public void testExists() throws ExecutionException, InterruptedException {
        //#exists
        Path filePath = Paths.get("/tmt/trmobone/assembly/hcd.conf");

        // create file using admin API
        adminApi.create(filePath, ConfigData.fromString(defaultStrConf), false, "commit config file").get();

        Boolean exists = clientApi.exists(filePath).get();
        Assert.assertTrue(exists);
        //#exists
    }

    @Test
    public void testGetActive() throws ExecutionException, InterruptedException {
        //#getActive
        // construct the path
        Path filePath = Paths.get("/tmt/trmobone/assembly/hcd.conf");

        adminApi.create(filePath, ConfigData.fromString(defaultStrConf), false, "First commit").get();

        ConfigData activeFile = clientApi.getActive(filePath).get().get();
        Assert.assertEquals(activeFile.toJConfigObject(mat).get().getString("foo.bar.baz"), "1234");
        //#getActive
    }

    @Test
    public void testCreateUpdateDelete() throws ExecutionException, InterruptedException, URISyntaxException, IOException {
        //#create
        //construct ConfigData from String containing ASCII text
        String configString = "axisName11111 = tromboneAxis\naxisName22222 = tromboneAxis2\naxisName3 = tromboneAxis3333";
        ConfigData config1 = ConfigData.fromString(configString);

        //construct ConfigData from a local file containing binary data
        URI srcFilePath = getClass().getClassLoader().getResource("smallBinary.bin").toURI();
        ConfigData config2 = ConfigData.fromPath(Paths.get(srcFilePath));

        ConfigId id1 = adminApi.create(Paths.get("/hcd/trombone/overnight.conf"), config1, false, "review done").get();
        ConfigId id2 = adminApi.create(Paths.get("/hcd/trombone/firmware.bin"), config2, true, "smoke test done").get();

        //CAUTION: for demo example setup these IDs are returned. Don't assume them in production setup.
        Assert.assertEquals(id1, new ConfigId("1"));
        Assert.assertEquals(id2, new ConfigId("3"));
        //#create

        //#update
        Path destPath = Paths.get("/hcd/trombone/overnight.conf");
        ConfigId newId = adminApi.update(destPath, ConfigData.fromString(defaultStrConf), "added debug statements").get();

        //validate the returned id
        Assert.assertEquals(newId, new ConfigId("5"));
        //#update

        //#delete
        Path unwantedFilePath = Paths.get("/hcd/trombone/overnight.conf");
        adminApi.delete(unwantedFilePath, "no longer needed").get();
        Assert.assertEquals(adminApi.getLatest(unwantedFilePath).get(), Optional.empty());
        //#delete
    }

    @Test
    public void testGetById() throws ExecutionException, InterruptedException, URISyntaxException, IOException {
        //#getById
        Path filePath = Paths.get("/tmt/trmobone/assembly/hcd.conf");
        ConfigId id = adminApi.create(filePath, ConfigData.fromString(defaultStrConf), false, "First commit").get();

        //validate
        ConfigData actualData = adminApi.getById(filePath, id).get().get();
        Assert.assertEquals(defaultStrConf, actualData.toJStringF(mat).get());
        //#getById
    }

    @Test
    public void testGetLatest() throws ExecutionException, InterruptedException, URISyntaxException, IOException {
        //#getLatest
        //create a file
        Path filePath = Paths.get("/test.conf");
        ConfigId id = adminApi.create(filePath, ConfigData.fromString(defaultStrConf), false, "initial configuration").get();

        //override the contents
        String newContent = "I changed the contents!!!";
        adminApi.update(filePath, ConfigData.fromString(newContent), "changed!!").get();

        //get the latest file
        ConfigData newConfigData = adminApi.getLatest(filePath).get().get();
        //validate
        Assert.assertEquals(newConfigData.toJStringF(mat).get(), newContent);
        //#getLatest
    }

    @Test
    public void testGetByTime() throws ExecutionException, InterruptedException, URISyntaxException, IOException {
        //#getByTime
        Instant tInitial = Instant.now();

        //create a file
        Path filePath = Paths.get("/test.conf");
        ConfigId id = adminApi.create(filePath, ConfigData.fromString(defaultStrConf), false, "initial configuration").get();

        //override the contents
        String newContent = "I changed the contents!!!";
        adminApi.update(filePath, ConfigData.fromString(newContent), "changed!!").get();

        ConfigData initialData = adminApi.getByTime(filePath, tInitial).get().get();
        Assert.assertEquals(defaultStrConf, initialData.toJStringF(mat).get());

        ConfigData latestData = adminApi.getByTime(filePath, Instant.now()).get().get();
        Assert.assertEquals(newContent, latestData.toJStringF(mat).get());
        //#getByTime
    }

    @Test
    public void testList() throws ExecutionException, InterruptedException, URISyntaxException, IOException {
        //#list
        Path trombonePath = Paths.get("a/c/trombone.conf");
        Path hcdPath = Paths.get("a/b/c/hcd/hcd.conf");
        Path fits1Path = Paths.get("a/b/assembly/assembly1.fits");
        Path fits2Path = Paths.get("a/b/c/assembly/assembly2.fits");
        Path testConfPath = Paths.get("testing/test.conf");

        String comment = "initial commit";

        //create files
        ConfigId tromboneId = adminApi.create(trombonePath, ConfigData.fromString(defaultStrConf), true, comment).get();
        ConfigId hcdId = adminApi.create(hcdPath, ConfigData.fromString(defaultStrConf), false, comment).get();
        ConfigId fits1Id = adminApi.create(fits1Path, ConfigData.fromString(defaultStrConf), true, comment).get();
        ConfigId fits2Id = adminApi.create(fits2Path, ConfigData.fromString(defaultStrConf), false, comment).get();
        ConfigId testId = adminApi.create(testConfPath, ConfigData.fromString(defaultStrConf), true, comment).get();

        //retrieve full list; for demonstration purpose validate return values
        Assert.assertEquals(new HashSet<ConfigId>(Arrays.asList(tromboneId, hcdId, fits1Id, fits2Id, testId)),
            adminApi.list().get().stream().map(ConfigFileInfo::id).collect(Collectors.toSet()));

        //retrieve list of files based on type; for demonstration purpose validate return values
        Assert.assertEquals(new HashSet<>(Arrays.asList(tromboneId, fits1Id, testId)),
            adminApi.list(JFileType.Annex).get().stream().map(ConfigFileInfo::id).collect(Collectors.toSet()));
        Assert.assertEquals(new HashSet<>(Arrays.asList(hcdId, fits2Id)),
            adminApi.list(JFileType.Normal).get().stream().map(ConfigFileInfo::id).collect(Collectors.toSet()));

        //retrieve list using pattern; for demonstration purpose validate return values
        Assert.assertEquals(new HashSet<>(Arrays.asList(tromboneId, hcdId, testId)),
            adminApi.list(".*.conf").get().stream().map(ConfigFileInfo::id).collect(Collectors.toSet()));
        //retrieve list using pattern and file type; for demonstration purpose validate return values
        Assert.assertEquals(new HashSet<>(Arrays.asList(tromboneId, testId)),
            adminApi.list(JFileType.Annex, ".*.conf").get().stream().map(ConfigFileInfo::id).collect(Collectors.toSet()));
        Assert.assertEquals(new HashSet<>(Arrays.asList(tromboneId)),
            adminApi.list(JFileType.Annex, "a/c.*").get().stream().map(ConfigFileInfo::id).collect(Collectors.toSet()));
        Assert.assertEquals(new HashSet<>(Arrays.asList(testId)),
            adminApi.list(JFileType.Annex, "test.*").get().stream().map(ConfigFileInfo::id).collect(Collectors.toSet()));
        //#list
    }

    @Test
    public void testHistory() throws ExecutionException, InterruptedException, URISyntaxException, IOException {
        //#history
        Path filePath = Paths.get("/a/test.conf");
        ConfigId id0 = adminApi.create(filePath, ConfigData.fromString(defaultStrConf), false, "first commit").get();

        //override the contents twice
        Instant tBeginUpdate = Instant.now();
        ConfigId id1 = adminApi.update(filePath, ConfigData.fromString("changing contents"), "second commit").get();
        ConfigId id2 = adminApi.update(filePath, ConfigData.fromString("changing contents again"), "third commit").get();
        Instant tEndUpdate = Instant.now();

        //full file history
        List<ConfigFileRevision> fullHistory = adminApi.history(filePath).get();
        Assert.assertEquals(new ArrayList<>(Arrays.asList(id2, id1, id0)),
            fullHistory.stream().map(ConfigFileRevision::id).collect(Collectors.toList()));
        Assert.assertEquals(new ArrayList<>(Arrays.asList("third commit", "second commit", "first commit")),
            fullHistory.stream().map(ConfigFileRevision::comment).collect(Collectors.toList()));

        //drop initial revision and take only update revisions
        Assert.assertEquals(new ArrayList<>(Arrays.asList(id2, id1)),
            adminApi.history(filePath, tBeginUpdate, tEndUpdate).get().stream().map(ConfigFileRevision::id).collect(Collectors.toList()));
        //take last two revisions
        Assert.assertEquals(new ArrayList<>(Arrays.asList(id2, id1)),
            adminApi.history(filePath, 2).get().stream().map(ConfigFileRevision::id).collect(Collectors.toList()));
        //#history
    }

    @Test
    public void testActiveFileManagement() throws ExecutionException, InterruptedException, URISyntaxException, IOException {
        //#active-file-mgmt
        Instant tBegin = Instant.now();
        Path filePath = Paths.get("/a/test.conf");

        //create will make the 1st revision active with a default comment
        ConfigId id1 = adminApi.create(filePath, ConfigData.fromString(defaultStrConf), false, "first commit").get();
        Assert.assertEquals(new ArrayList<>(Arrays.asList(id1)),
            adminApi.historyActive(filePath).get().stream().map(ConfigFileRevision::id).collect(Collectors.toList()));
        //ensure active version is set
        Assert.assertEquals(id1, adminApi.getActiveVersion(filePath).get().get());

        //override the contents four times
        adminApi.update(filePath, ConfigData.fromString("changing contents"), "second").get();
        ConfigId id3 = adminApi.update(filePath, ConfigData.fromString("changing contents again"), "third").get();
        ConfigId id4 = adminApi.update(filePath, ConfigData.fromString("final contents"), "fourth").get();
        ConfigId id5 = adminApi.update(filePath, ConfigData.fromString("final final contents"), "fifth").get();

        //update doesn't change the active revision
        Assert.assertEquals(id1, adminApi.getActiveVersion(filePath).get().get());

        //play with active version
        adminApi.setActiveVersion(filePath, id3, "id3 active").get();
        adminApi.setActiveVersion(filePath, id4, "id4 active").get();
        Assert.assertEquals(id4, adminApi.getActiveVersion(filePath).get().get());
        Instant tEnd = Instant.now();

        //reset active version to latest
        adminApi.resetActiveVersion(filePath, "latest active").get();
        Assert.assertEquals(id5, adminApi.getActiveVersion(filePath).get().get());
        //finally set initial version as active
        adminApi.setActiveVersion(filePath, id1, "id1 active").get();
        Assert.assertEquals(id1, adminApi.getActiveVersion(filePath).get().get());

        //validate full history
        List<ConfigFileRevision> fullHistory = adminApi.historyActive(filePath).get();
        Assert.assertEquals(new ArrayList<>(Arrays.asList(id1, id5, id4, id3, id1)),
                fullHistory.stream().map(ConfigFileRevision::id).collect(Collectors.toList()));
        Assert.assertEquals(new ArrayList<>(Arrays.asList("id1 active", "latest active", "id4 active", "id3 active",
                "initializing active file with the first version")),
                fullHistory.stream().map(ConfigFileRevision::comment).collect(Collectors.toList()));

        //drop initial revision and take only update revisions
        List<ConfigFileRevision> fragmentedHistory = adminApi.historyActive(filePath, tBegin, tEnd).get();
        Assert.assertEquals(3, fragmentedHistory.size());

        //take last three revisions
        Assert.assertEquals(new ArrayList<>(Arrays.asList(id1, id5, id4)),
                adminApi.historyActive(filePath, 3).get().stream().map(ConfigFileRevision::id).collect(Collectors.toList()));

        //get contents of active version at a specified instance
        String initialContents = adminApi.getActiveByTime(filePath, tBegin).get().get().toJStringF(mat).get();
        Assert.assertEquals(defaultStrConf, initialContents);
        //#active-file-mgmt
    }

    @Test
    public void testGetMetadata() throws ExecutionException, InterruptedException {
        //#getMetadata
        ConfigMetadata metadata = adminApi.getMetadata().get();
        //repository path must not be empty
        Assert.assertNotEquals(metadata.repoPath(), "");
        //#getMetadata
    }
}

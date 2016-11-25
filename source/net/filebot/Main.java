package net.filebot;

import static java.awt.GraphicsEnvironment.*;
import static java.util.Arrays.*;
import static java.util.stream.Collectors.*;
import static net.filebot.Logging.*;
import static net.filebot.Settings.*;
import static net.filebot.util.ExceptionUtilities.*;
import static net.filebot.util.FileUtilities.*;
import static net.filebot.util.XPathUtilities.*;
import static net.filebot.util.ui.SwingUI.*;

import java.awt.Dialog.ModalityType;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.security.CodeSource;
import java.security.Permission;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.security.Policy;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;

import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import org.kohsuke.args4j.CmdLineException;
import org.w3c.dom.Document;

import net.filebot.cli.ArgumentBean;
import net.filebot.cli.ArgumentProcessor;
import net.filebot.cli.CmdlineException;
import net.filebot.format.ExpressionFormat;
import net.filebot.mac.MacAppUtilities;
import net.filebot.ui.FileBotMenuBar;
import net.filebot.ui.GettingStartedStage;
import net.filebot.ui.MainFrame;
import net.filebot.ui.NotificationHandler;
import net.filebot.ui.PanelBuilder;
import net.filebot.ui.SinglePanelFrame;
import net.filebot.ui.SupportDialog;
import net.filebot.ui.transfer.FileTransferable;
import net.filebot.util.PreferencesMap.PreferencesEntry;
import net.filebot.util.TeePrintStream;
import net.filebot.util.ui.SwingEventBus;
import net.filebot.win.WinAppUtilities;
import net.miginfocom.swing.MigLayout;

public class Main {

	public static void main(String[] argv) {
		try {
			// parse arguments
			ArgumentBean args = new ArgumentBean(argv);

			// just print help message or version string and then exit
			if (args.printHelp()) {
				log.info(String.format("%s%n%n%s", getApplicationIdentifier(), args.usage()));
				System.exit(0);
			}

			if (args.printVersion()) {
				log.info(String.join(" / ", getApplicationIdentifier(), getJavaRuntimeIdentifier(), getSystemIdentifier()));
				System.exit(0);
			}

			if (args.clearCache() || args.clearUserData()) {
				// clear cache must be called manually
				if (System.console() == null) {
					log.severe("`filebot -clear-cache` has been disabled due to abuse.");
					System.exit(1);
				}

				// clear persistent user preferences
				if (args.clearUserData()) {
					log.info("Reset preferences");
					Settings.forPackage(Main.class).clear();
				}

				// clear caches
				if (args.clearCache()) {
					log.info("Clear cache");
					for (File folder : getChildren(ApplicationFolder.Cache.getCanonicalFile(), FOLDERS)) {
						log.fine("* Delete " + folder);
						delete(folder);
					}
				}

				// just clear cache and/or settings and then exit
				System.exit(0);
			}

			// make sure we can access application arguments at any time
			setApplicationArguments(args);

			// update system properties
			initializeSystemProperties(args);
			initializeLogging(args);

			// make sure java.io.tmpdir exists
			createFolders(ApplicationFolder.Temp.get());

			// initialize this stuff before anything else
			CacheManager.getInstance();
			initializeSecurityManager();

			// initialize history spooler
			HistorySpooler.getInstance().setPersistentHistoryEnabled(useRenameHistory());

			// CLI mode => run command-line interface and then exit
			if (args.runCLI()) {
				int status = new ArgumentProcessor().run(args);
				System.exit(status);
			}

			if (isHeadless()) {
				log.info(String.format("%s / %s (headless)%n%n%s", getApplicationIdentifier(), getJavaRuntimeIdentifier(), args.usage()));
				System.exit(1);
			}

			// GUI mode => start user interface
			SwingUtilities.invokeLater(() -> {
				startUserInterface(args);

				// run background tasks
				newSwingWorker(() -> onStart(args)).execute();
			});
		} catch (CmdLineException e) {
			// illegal arguments => print CLI error message
			log.severe(e::getMessage);
			System.exit(1);
		} catch (Throwable e) {
			// unexpected error => dump stack
			debug.log(Level.SEVERE, "Error during startup: " + getRootCause(e), e);
			System.exit(1);
		}
	}

	private static void onStart(ArgumentBean args) {
		// publish file arguments
		List<File> files = args.getFiles(false);
		if (files.size() > 0) {
			SwingEventBus.getInstance().post(new FileTransferable(files));
		}

		// preload media.types (when loaded during DnD it will freeze the UI for a few hundred milliseconds)
		MediaTypes.getDefault();

		// JavaFX is used for ProgressMonitor and GettingStartedDialog
		try {
			initJavaFX();
		} catch (Throwable e) {
			log.log(Level.SEVERE, "Failed to initialize JavaFX. Please install JavaFX.", e);
		}

		// check if application help should be shown
		if (!"skip".equals(System.getProperty("application.help"))) {
			try {
				checkGettingStarted();
			} catch (Throwable e) {
				debug.log(Level.WARNING, "Failed to show Getting Started help", e);
			}
		}

		// check for application updates
		if (!"skip".equals(System.getProperty("application.update"))) {
			try {
				checkUpdate();
			} catch (Throwable e) {
				debug.log(Level.WARNING, "Failed to check for updates", e);
			}
		}
	}

	private static void startUserInterface(ArgumentBean args) {
		// use native LaF an all platforms (use platform-independent laf for standalone jar deployment)
		if (isExecutableJar()) {
			setNimbusLookAndFeel();
		} else {
			setSystemLookAndFeel();
		}

		// start multi panel or single panel frame
		PanelBuilder[] panels = PanelBuilder.defaultSequence();

		if (args.mode != null) {
			panels = stream(panels).filter(p -> p.getName().matches(args.mode)).toArray(PanelBuilder[]::new); // only selected panels

			if (panels.length == 0) {
				throw new CmdlineException("Illegal mode: " + args.mode);
			}
		}

		JFrame frame = panels.length > 1 ? new MainFrame(panels) : new SinglePanelFrame(panels[0]);

		try {
			restoreWindowBounds(frame, Settings.forPackage(MainFrame.class)); // restore previous size and location
		} catch (Exception e) {
			frame.setLocation(120, 80); // make sure the main window is not displayed out of screen bounds
		}

		frame.addWindowListener(windowClosed(evt -> {
			evt.getWindow().setVisible(false);

			// make sure any long running operations are done now and not later on the shutdown hook thread
			HistorySpooler.getInstance().commit();
			SupportDialog.maybeShow();

			System.exit(0);
		}));

		// configure main window
		if (isMacApp()) {
			// Mac specific configuration
			MacAppUtilities.initializeApplication();
			MacAppUtilities.setWindowCanFullScreen(frame);
			MacAppUtilities.setDefaultMenuBar(FileBotMenuBar.createHelp());
			MacAppUtilities.setOpenFileHandler(openFiles -> SwingEventBus.getInstance().post(new FileTransferable(openFiles)));
		} else if (isUbuntuApp()) {
			// Ubuntu specific configuration
			String options = System.getenv("JAVA_TOOL_OPTIONS");
			if (options != null && options.contains("jayatanaag.jar")) {
				// menu should be rendered via JAyatana on Ubuntu 15.04 and higher
				frame.setJMenuBar(FileBotMenuBar.createHelp());
			}
			frame.setIconImages(ResourceManager.getApplicationIcons());
		} else if (isWindowsApp()) {
			// Windows specific configuration
			if (!isAppStore()) {
				WinAppUtilities.setAppUserModelID(Settings.getApplicationUserModelID()); // support Windows 7 taskbar behaviours (not necessary for Windows 10 apps)
			}
			frame.setIconImages(ResourceManager.getApplicationIcons());
		} else {
			// generic Linux/FreeBSD/Solaris configuration
			frame.setIconImages(ResourceManager.getApplicationIcons());
		}

		// start application
		frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
		frame.setVisible(true);
	}

	/**
	 * Show update notifications if updates are available
	 */
	private static void checkUpdate() throws Exception {
		Cache cache = Cache.getCache(getApplicationName(), CacheType.Persistent);
		Document dom = cache.xml("update.url", s -> new URL(getApplicationProperty(s))).expire(Cache.ONE_WEEK).retry(0).get();

		// parse update xml
		Map<String, String> update = streamElements(dom.getFirstChild()).collect(toMap(n -> n.getNodeName(), n -> n.getTextContent().trim()));

		// check if update is required
		int latestRev = Integer.parseInt(update.get("revision"));
		int currentRev = getApplicationRevisionNumber();

		if (latestRev > currentRev && currentRev > 0) {
			SwingUtilities.invokeLater(() -> {
				JDialog dialog = new JDialog(JFrame.getFrames()[0], update.get("title"), ModalityType.APPLICATION_MODAL);
				JPanel pane = new JPanel(new MigLayout("fill, nogrid, insets dialog"));
				dialog.setContentPane(pane);

				pane.add(new JLabel(ResourceManager.getIcon("window.icon.medium")), "aligny top");
				pane.add(new JLabel(update.get("message")), "aligny top, gap 10, wrap paragraph:push");

				pane.add(newButton("Download", ResourceManager.getIcon("dialog.continue"), evt -> {
					openURI(update.get("download"));
					dialog.setVisible(false);
				}), "tag ok");

				pane.add(newButton("Details", ResourceManager.getIcon("action.report"), evt -> {
					openURI(update.get("discussion"));
				}), "tag help2");

				pane.add(newButton("Ignore", ResourceManager.getIcon("dialog.cancel"), evt -> {
					dialog.setVisible(false);
				}), "tag cancel");

				dialog.pack();
				dialog.setLocation(getOffsetLocation(dialog.getOwner()));
				dialog.setVisible(true);
			});
		}
	}

	/**
	 * Show Getting Started to new users
	 */
	private static void checkGettingStarted() throws Exception {
		PreferencesEntry<String> started = Settings.forPackage(Main.class).entry("getting.started").defaultValue("0");
		if ("0".equals(started.getValue())) {
			started.setValue("1");
			started.flush();

			// open Getting Started
			SwingUtilities.invokeLater(GettingStartedStage::start);
		}
	}

	private static void restoreWindowBounds(JFrame window, Settings settings) {
		// store bounds on close
		window.addWindowListener(windowClosed(evt -> {
			// don't save window bounds if window is maximized
			if (!isMaximized(window)) {
				settings.put("window.x", String.valueOf(window.getX()));
				settings.put("window.y", String.valueOf(window.getY()));
				settings.put("window.width", String.valueOf(window.getWidth()));
				settings.put("window.height", String.valueOf(window.getHeight()));
			}
		}));

		// restore bounds
		int x = Integer.parseInt(settings.get("window.x"));
		int y = Integer.parseInt(settings.get("window.y"));
		int width = Integer.parseInt(settings.get("window.width"));
		int height = Integer.parseInt(settings.get("window.height"));
		window.setBounds(x, y, width, height);
	}

	/**
	 * Initialize default SecurityManager and grant all permissions via security policy. Initialization is required in order to run {@link ExpressionFormat} in a secure sandbox.
	 */
	private static void initializeSecurityManager() {
		try {
			// initialize security policy used by the default security manager
			// because default the security policy is very restrictive (e.g. no FilePermission)
			Policy.setPolicy(new Policy() {

				@Override
				public boolean implies(ProtectionDomain domain, Permission permission) {
					// all permissions
					return true;
				}

				@Override
				public PermissionCollection getPermissions(CodeSource codesource) {
					// VisualVM can't connect if this method does return
					// a checked immutable PermissionCollection
					return new Permissions();
				}
			});

			// set default security manager
			System.setSecurityManager(new SecurityManager());
		} catch (Exception e) {
			// security manager was probably set via system property
			debug.log(Level.WARNING, e.getMessage(), e);
		}
	}

	public static void initializeSystemProperties(ArgumentBean args) {
		System.setProperty("http.agent", String.format("%s %s", getApplicationName(), getApplicationVersion()));
		System.setProperty("sun.net.client.defaultConnectTimeout", "10000");
		System.setProperty("sun.net.client.defaultReadTimeout", "60000");

		System.setProperty("swing.crossplatformlaf", "javax.swing.plaf.nimbus.NimbusLookAndFeel");
		System.setProperty("grape.root", ApplicationFolder.AppData.path("grape").getPath());
		System.setProperty("org.apache.commons.logging.Log", "org.apache.commons.logging.impl.NoOpLog");

		if (args.unixfs) {
			System.setProperty("unixfs", "true");
		}

		if (args.disableExtendedAttributes) {
			System.setProperty("useExtendedFileAttributes", "false");
			System.setProperty("useCreationDate", "false");
		}
	}

	public static void initializeLogging(ArgumentBean args) throws IOException {
		if (args.runCLI()) {
			// CLI logging settings
			log.setLevel(args.getLogLevel());
		} else {
			// GUI logging settings
			log.setLevel(Level.INFO);
			log.addHandler(new NotificationHandler(getApplicationName()));

			// log errors to file
			try {
				Handler error = createSimpleFileHandler(ApplicationFolder.AppData.path("error.log"), Level.WARNING);
				log.addHandler(error);
				debug.addHandler(error);
			} catch (Exception e) {
				debug.log(Level.WARNING, "Failed to initialize error log", e);
			}
		}

		// tee stdout and stderr to log file if set
		if (args.logFile != null) {
			File logFile = new File(args.logFile);
			if (!logFile.isAbsolute()) {
				logFile = new File(ApplicationFolder.AppData.path("logs"), logFile.getPath()).getAbsoluteFile(); // by default resolve relative paths against {applicationFolder}/logs/{logFile}
			}
			if (!logFile.exists() && !logFile.getParentFile().mkdirs() && !logFile.createNewFile()) {
				throw new IOException("Failed to create log file: " + logFile);
			}

			// open file channel and lock
			FileChannel logChannel = FileChannel.open(logFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
			if (args.logLock) {
				try {
					log.config("Locking " + logFile);
					logChannel.lock();
				} catch (Exception e) {
					throw new IOException("Failed to acquire lock: " + logFile, e);
				}
			}

			OutputStream out = Channels.newOutputStream(logChannel);
			System.setOut(new TeePrintStream(out, true, "UTF-8", System.out));
			System.setErr(new TeePrintStream(out, true, "UTF-8", System.err));
		}
	}

}

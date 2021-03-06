package xyz.posse.clicker.core;

import org.jnativehook.GlobalScreen;
import org.jnativehook.NativeHookException;
import org.jnativehook.dispatcher.SwingDispatchService;
import org.jnativehook.keyboard.NativeKeyEvent;
import org.jnativehook.keyboard.NativeKeyListener;
import org.jnativehook.mouse.NativeMouseEvent;
import org.jnativehook.mouse.NativeMouseMotionListener;
import xyz.posse.clicker.GUI.ClickerWindowListener;
import xyz.posse.clicker.GUI.MainWindow;
import xyz.posse.clicker.GUI.SettingsWindow;
import xyz.posse.clicker.telegram.Telegram;

import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.*;

public class Clicker extends Robot implements ClickerWindowListener, NativeKeyListener, NativeMouseMotionListener {

    private static final int REFRESH_DELAY = 1;
    private static final int DRAG_DELAY = 150;
    private static final int DRAG_STEPS = 10;
    private static final Logger commonLogger = Logger.getLogger(Clicker.class.getName());
    private final MainWindow window;
    private final Settings settings;
    private final ScriptForClicker script;
    private final Clipboard clipboard;
    private final SettingsWindow settingsWindow;
    private final String start = "start";
    private final String stop = "stop";
    private final Map<Integer, Color> colors = new HashMap<>();
    private Telegram telegram;
    private Thread scriptThread;
    private NativeMouseEvent mouseCoordinates;
    private int x;
    private int y;
    private Color color;
    private long lastTimestamp = System.currentTimeMillis();
    private boolean isChangeStartBtn;
    private boolean isChangeStopBtn;
    private boolean isEditorActive;
    private String telegramChatID;
    private String telegramToken;

    public Clicker(ScriptForClicker script, String... options) throws AWTException {
        this.script = script;
        scriptThread = new Thread((Runnable) script);
        telegram = new Telegram();
        window = new MainWindow(this, options);
        if (options.length > 0) script.optionSelected(options[0]);
        window.setStatus(scriptThread.isAlive());
        settings = new Settings();
        settingsWindow = new SettingsWindow(this);
        settingsWindow.setNewButton(start, settings.getKeyStart());
        settingsWindow.setNewButton(stop, settings.getKeyStop());
        window.setAlwaysOnTop(settings.isAlwaysOnTop());
        setWindowKeys();
        waitForMainWindow();
        clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        initLoggers();
        registerHook();
    }

    private static void registerHook() {
        try {
            GlobalScreen.registerNativeHook();
        } catch (NativeHookException ex) {
            System.err.println("There was a problem registering the native hook.");
            System.err.println(ex.getMessage());
            System.exit(1);
        }
    }

    private static void unregisterHook() {
        try {
            GlobalScreen.unregisterNativeHook();
        } catch (NativeHookException e) {
            e.printStackTrace();
        }
    }

    private void initLoggers() {
        Handler fileHandler = getNewFileHandler();
        fileHandler.setLevel(Level.INFO);
        ConsoleHandler consoleHandler = new ConsoleHandler();
        consoleHandler.setLevel(Level.FINE);
        commonLogger.setLevel(Level.FINE);
        commonLogger.addHandler(consoleHandler);
        commonLogger.addHandler(fileHandler);
        commonLogger.setUseParentHandlers(false);
        Logger logger = Logger.getLogger(GlobalScreen.class.getPackage().getName());
        logger.setLevel(Level.OFF);
        logger.setUseParentHandlers(false);
        GlobalScreen.setEventDispatcher(new SwingDispatchService());
        GlobalScreen.addNativeKeyListener(this);
        GlobalScreen.addNativeMouseMotionListener(this);
    }

    private void waitForMainWindow() {
        while (!window.isVisible()) {
            Thread.onSpinWait();
        }
    }

    private Handler getNewFileHandler() {
        FileHandler handler = null;
        try {
            handler = new FileHandler("Clicker.txt", 1024 * 100, 1, true);
            handler.setEncoding("UTF-8");
            handler.setFormatter(new Formatter() {
                @Override
                public String format(LogRecord record) {
                    DateFormat DATE_FORMAT = new SimpleDateFormat("[HH:mm:ss]");
                    return String.format(
                            "%s: %s", DATE_FORMAT.format(System.currentTimeMillis()),
                            record.getMessage()) + "\n";
                }
            });
        } catch (IOException e) {
            e.printStackTrace();
        }
        return handler;
    }

    public void click(int x, int y) {
        doClick(x, y);
    }

    public void click(Point point) {
        doClick(point.x, point.y);
    }

    private void doClick(int x, int y) {
        mouseMove(x, y);
        mousePress(InputEvent.BUTTON1_DOWN_MASK);
        mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
    }

    public void drag(int oldX, int oldY, int newX, int newY, int steps, int delay) {
        dragObject(oldX, oldY, newX, newY, steps, delay);
    }

    public void drag(int oldX, int oldY, int newX, int newY, int steps) {
        dragObject(oldX, oldY, newX, newY, steps, DRAG_DELAY);
    }

    public void drag(int oldX, int oldY, int newX, int newY) {
        dragObject(oldX, oldY, newX, newY, DRAG_STEPS, DRAG_DELAY);
    }

    public void drag(Point start, Point end, int steps, int delay) {
        dragObject(start.x, start.y, end.x, end.y, steps, delay);
    }

    public void drag(Point start, Point end, int steps) {
        dragObject(start.x, start.y, end.x, end.y, steps, DRAG_DELAY);
    }

    public void drag(Point start, Point end) {
        dragObject(start.x, start.y, end.x, end.y, DRAG_STEPS, DRAG_DELAY);
    }

    private synchronized void dragObject(int oldX, int oldY, int newX, int newY, int steps, int delay) {
        try {
            mouseMove(oldX, oldY);
            mousePress(InputEvent.BUTTON1_DOWN_MASK);
            script.wait(delay);
            if (steps == 0) steps = 1;
            int xDiff = (newX - oldX) / steps;
            int yDiff = (newY - oldY) / steps;
            for (int i = 1; i <= steps; i++) {
                mouseMove(oldX + xDiff * i, oldY + yDiff * i);
                script.wait(delay);
            }
            mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public int getColor(int x, int y) {
        Rectangle rect = new Rectangle(x, y, 1, 1);
        BufferedImage capture = createScreenCapture(rect);
        return capture.getRGB(0, 0);
    }

    public void putLog(String msg) {
        commonLogger.info(msg);
    }

    public void startTelegram(String msg, int delay, boolean repeat) {
        if (!telegram.isAlive()) {
            telegram = new Telegram();
            telegram.setChatID(telegramChatID);
            telegram.setApiToken(telegramToken);
            telegram.setName("Telegram");
            telegram.start();
        } else {
            telegram.setCountdown(0);
        }
        telegram.setMsg(msg);
        telegram.setDelay(delay);
        telegram.setRepeat(repeat);
    }

    public void stopTelegram() {
        telegram.interrupt();
    }

    @Override
    public void start() {
        if (!scriptThread.isAlive()) {
            scriptThread = new Thread((Runnable) script);
            scriptThread.setName("Clicker");
            scriptThread.start();
        }
        window.setStatus(scriptThread.isAlive());
    }

    @Override
    public void stop() {
        scriptThread.interrupt();
        window.setStatus(false);
        window.requestFocus();
        stopTelegram();
    }

    @Override
    public void settingsClicked() {
        settingsWindow.setVisible(true);
    }

    @Override
    public void changeStartBtn() {
        isChangeStartBtn = true;
        settingsWindow.setNewButton(start, "");
    }

    @Override
    public void changeStopBtn() {
        isChangeStopBtn = true;
        settingsWindow.setNewButton(stop, "");
    }

    @Override
    public void alwaysOnTop(boolean onTop) {
        settings.setAlwaysOnTop(onTop);
    }

    @Override
    public void optionSelected(String option) {
        script.optionSelected(option);
    }

    @Override
    public void logPressed() {
        try {
            File file = new File("Clicker.txt");
            Desktop.getDesktop().open(file);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void editorPressed(boolean editorPressed) {
        isEditorActive = editorPressed;
    }

    @Override
    public void nativeKeyTyped(NativeKeyEvent nativeKeyEvent) {
        //don't need
    }

    @Override
    public synchronized void nativeKeyPressed(NativeKeyEvent nativeKeyEvent) {
        try {
            String key = NativeKeyEvent.getKeyText(nativeKeyEvent.getKeyCode());
            commonLogger.fine("Key pressed: " + key);
            if (key.equals(settings.getKeySave())) {
                setCurrentMousePositionData();
                window.setSavedData(x, y, color);
                StringSelection stringSelection = new StringSelection(
                        "if(clicker.getColor(" + x + ", " + y + ") == " +
                                color.getRGB() + ") {\n" + "clicker.putLog(\"\");\n" + "}"
                );
                clipboard.setContents(stringSelection, stringSelection);
            } else if (key.equals(settings.getKeyStop())) {
                stop();
            } else if (key.equals(settings.getKeyStart())) {
                start();
            }
            if (isChangeStartBtn || isChangeStopBtn) {
                if (isChangeStartBtn) {
                    settings.setKeyStart(key);
                    settingsWindow.setNewButton("start", key);
                } else {
                    settings.setKeyStop(key);
                    settingsWindow.setNewButton("stop", key);
                }
                setWindowKeys();
                isChangeStartBtn = false;
                isChangeStopBtn = false;
                wait(500);
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void nativeKeyReleased(NativeKeyEvent nativeKeyEvent) {
        //don't need
    }

    @Override
    public void nativeMouseMoved(NativeMouseEvent nativeMouseEvent) {
        mouseCoordinates = nativeMouseEvent;
        if (isEditorActive) {
            if (System.currentTimeMillis() - lastTimestamp > REFRESH_DELAY) {
                setCurrentMousePositionData();
                window.setColorInfo(x, y, color);
                lastTimestamp = System.currentTimeMillis();
            }
        }
    }

    @Override
    public void nativeMouseDragged(NativeMouseEvent nativeMouseEvent) {
        //don't need
    }

    public void setTelegramData(String chatID, String token) {
        telegramChatID = chatID;
        telegramToken = token;
    }

    private void setCurrentMousePositionData() {
        x = mouseCoordinates.getX();
        y = mouseCoordinates.getY();
        color = getPixelColor(x, y);
    }

    @Override
    public Color getPixelColor(int x, int y) {
        Rectangle rect = new Rectangle(x, y, 1, 1);
        BufferedImage capture = createScreenCapture(rect);
        int colorIndex = capture.getRGB(0, 0);
        if (!colors.containsKey(colorIndex)) colors.put(colorIndex, new Color(colorIndex));
        return colors.get(colorIndex);
    }

    private void setWindowKeys() {
        window.setKeys(settings.getKeySave(), settings.getKeyStart(), settings.getKeyStop());
    }
}
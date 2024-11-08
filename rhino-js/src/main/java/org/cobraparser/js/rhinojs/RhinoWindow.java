/*
    GNU LESSER GENERAL PUBLIC LICENSE
    Copyright (C) 2006 The Lobo Project

    This library is free software; you can redistribute it and/or
    modify it under the terms of the GNU Lesser General Public
    License as published by the Free Software Foundation; either
    version 2.1 of the License, or (at your option) any later version.

    This library is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
    Lesser General Public License for more details.

    You should have received a copy of the GNU Lesser General Public
    License along with this library; if not, write to the Free Software
    Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA

    Contact info: lobochief@users.sourceforge.net
 */
/*
 * Created on Nov 12, 2005
 */
package org.cobraparser.js.rhinojs;

import org.cobraparser.html.HtmlRendererContext;
import org.cobraparser.html.domimpl.*;
import org.cobraparser.html.js.*;
import org.cobraparser.js.AbstractScriptableDelegate;
import org.cobraparser.js.HideFromJS;
import org.cobraparser.ua.UserAgentContext;
import org.cobraparser.ua.UserAgentContext.Request;
import org.cobraparser.ua.UserAgentContext.RequestKind;
import org.cobraparser.util.ID;
import org.mozilla.javascript.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.css.CSS2Properties;
import org.w3c.dom.events.EventException;
import org.w3c.dom.events.EventListener;
import org.w3c.dom.html.HTMLCollection;
import org.w3c.dom.html.HTMLElement;
import org.w3c.dom.views.DocumentView;

import javax.swing.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.lang.ref.WeakReference;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class RhinoWindow extends AbstractScriptableDelegate implements Window {
    private static final Storage STORAGE = new Storage();

    private static final Logger logger = LoggerFactory.getLogger(RhinoWindow.class.getName());
    private static final Map<HtmlRendererContext, WeakReference<RhinoWindow>> CONTEXT_WINDOWS = new WeakHashMap<>();
    // private static final JavaClassWrapper IMAGE_WRAPPER =
    // JavaClassWrapperFactory.getInstance().getClassWrapper(Image.class);
    private static final JavaClassWrapper XMLHTTPREQUEST_WRAPPER = JavaClassWrapperFactory.getInstance()
        .getClassWrapper(XMLHttpRequest.class);

    private static final JavaClassWrapper PATH2D_WRAPPER = JavaClassWrapperFactory.getInstance()
        .getClassWrapper(CanvasPath2D.class);

    private static final JavaClassWrapper EVENT_WRAPPER = JavaClassWrapperFactory.getInstance()
        .getClassWrapper(RhinoEvent.class);

    // Timer ids should begin counting from 1 or more.
    // jQuery's ajax polling handler relies on a non-zero value (uses it as a boolean condition)
    // Chromium 37 starts counting from 1 while Firefox 32 starts counting from 2 (from developer consoles and plugins installed)
    private static int timerIdCounter = 1;

    private final HtmlRendererContext rcontext;
    private final UserAgentContext uaContext;

    private Navigator navigator;
    private RhinoScreen screen;
    private RhinoLocation location;
    private Map<Integer, TaskWrapper> taskMap;
    private volatile Document document;
    // private volatile HTMLDocumentImpl document;

    public RhinoWindow(final HtmlRendererContext rcontext, final UserAgentContext uaContext) {
        // TODO: Probably need to create a new Window instance
        // for every document. Sharing of Window state between
        // different documents is not correct.
        this.rcontext = rcontext;
        this.uaContext = uaContext;
    }

    private static int generateTimerID() {
        synchronized (logger) {
            return timerIdCounter++;
        }
    }

    public HtmlRendererContext getHtmlRendererContext() {
        return this.rcontext;
    }

    public UserAgentContext getUserAgentContext() {
        return this.uaContext;
    }

    private void clearState() {
        synchronized (this) {
            // windowClosing = true;
            if (document instanceof HTMLDocumentImpl) {
                ((HTMLDocumentImpl) document).stopEverything();
            }
            jsScheduler.stopAndWindUp(true);
            jsScheduler = new JSScheduler(this);
            eventTargetManager.reset();
            this.onWindowLoadHandler = null;

            this.forgetAllTasks();

            // Commenting out call to getWindowScope() since that creates a new scope which is wasteful
            // if we are going to destroy it anyway.
            // final Scriptable s = this.getWindowScope();
            final Scriptable s = this.windowScope;
            if (s != null) {
                final Object[] ids = s.getIds();
                for (final Object id : ids) {
                    if (id instanceof String) {
                        s.delete((String) id);
                    }
                    else if (id instanceof Integer) {
                        s.delete(((Integer) id).intValue());
                    }
                }
            }

            // This will ensure that a fresh scope will be created by getWindowScope() on the next call
            this.windowScope = null;
        }
    }

    @Override
    @HideFromJS
    public void setDocument(final Document document) {
        synchronized (this) {

            final Document prevDocument = this.document;
            if (prevDocument != document) {
                final Function onunload = this.onunload;
                if (onunload != null) {
                    final HTMLDocumentImpl oldDoc = (HTMLDocumentImpl) prevDocument;
                    Executor.executeFunction(this.getWindowScope(), onunload, oldDoc.getDocumentURL(), this.uaContext, windowContextFactory);
                    this.onunload = null;
                }

                // TODO: Should clearing of the state be done when window "unloads"?
                if (prevDocument != null) {
                    // Only clearing when the previous document was not null
                    // because state might have been set on the window before
                    // the very first document is added.
                    this.clearState();
                }
                // this.forgetAllTasks();
                this.initWindowScope(document);

                jobsOver.set(false);
                jsScheduler.start();

                this.document = document;
                // eventTargetManager.setNode(document);
            }
        }
    }

    @Override
    public DocumentView getDocument() {
        return (DocumentView) this.document;
    }

    public Document getDocumentNode() {
        return this.document;
    }

    private static final int JS_SCHED_POLL_INTERVAL_MILLIS = 100;
    private static final int JS_SCHED_JOIN_INTERVAL_MILLIS = JS_SCHED_POLL_INTERVAL_MILLIS * 2;

    private static final class JSScheduler extends Thread {
        private static final class ScheduledTask implements Comparable<ScheduledTask> {
            final int id;
            final JSTask task;

            public ScheduledTask(final int id, final JSTask task) {
                this.id = id;
                this.task = task;
            }

            @Override
            public int compareTo(final ScheduledTask other) {
                return task.compareTo(other.task);
            }

            @Override
            public boolean equals(final Object o) {
                if (o instanceof Integer) {
                    final Integer oId = (Integer) o;
                    return oId == id;
                }
                return false;
            }

            @Override
            public String toString() {
                return "Scheduled Task (" + id + ", " + task + ")";
            }
        }

        private final PriorityBlockingQueue<ScheduledTask> jsQueue = new PriorityBlockingQueue<>();
        private final AtomicBoolean running = new AtomicBoolean(false);

        private volatile boolean windowClosing = false;

        // TODO: This is not water tight for one reason, Windows are reused for different documents.
        // If they are always freshly created, the taskIdCounter will be more reliable.
        private volatile AtomicInteger taskIdCounter = new AtomicInteger(0);

        private String name;

        public JSScheduler(final RhinoWindow window) {
            super("JS Scheduler");
            this.name = "JS Sched " + (window.document == null ? "" : "" + window.document.getBaseURI());
        }

        @Override
        public void run() {
            setName(name);
            while (!windowClosing) {
                try {
                    ScheduledTask scheduledTask;
                    // TODO: uncomment if synchronization is necessary with the add methods
                    // synchronized (this) {
                    scheduledTask = jsQueue.poll(JS_SCHED_POLL_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
                    if (scheduledTask != null) {
                        // System.out.println("In " + window.document.getBaseURI() + "\n  Running task: " + scheduledTask);
                        // System.out.println("In " + name + "\n  Running task: " + scheduledTask);
                        running.set(true);
                        scheduledTask.task.run();
                        // System.out.println("Done task: " + scheduledTask);
                        // System.out.println("  Remaining tasks: " + jsQueue.size());
                    }
                }
                catch (final InterruptedException e) {
                    final int queueSize = jsQueue.size();
                    if (queueSize > 0) {
                        System.err.println("JS Scheduler was interrupted. Tasks remaining: " + jsQueue.size());
                    }
                }
                catch (final Exception e) {
                    e.printStackTrace();
                }
                catch (final WindowClosingError wce) {
                    // Javascript context detected a request for closing and bailed out.
                    assert (windowClosing);
                }
                finally {
                    running.set(false);
                }
            }
            // System.out.println("Exiting loop\n\n");
        }

        public void stopAndWindUp(final boolean blocking) {
            // System.out.println("Going to stop JS scheduler");
            windowClosing = true;

            /* TODO: If the thread refuses to join(), perhaps the thread could be interrupted and stopped in
             * the catch block of join() below. This could be done immediately, or scheduled for a stopping
             * in a separate collector Thread
             * */
            // this.interrupt();

            if (blocking) {
                try {
                    this.join(JS_SCHED_JOIN_INTERVAL_MILLIS);
                }
                catch (final InterruptedException e) {
                    e.printStackTrace();
                }
            }

      /*
      this.stop();
      */
        }

        public boolean isWindowClosing() {
            return windowClosing;
        }

        public void addJSTask(final JSTask task) {
            // synchronized (this) {
            jsQueue.add(new ScheduledTask(0, task));
            // }
        }

        public int addUniqueJSTask(final int oldId, final JSTask task) {
            // synchronized (this) {
            if (oldId != -1) {
                if (jsQueue.contains(oldId)) {
                    return oldId;
                }
        /*
        for (ScheduledTask t : jsQueue) {
          if (t.id == oldId) {
            // Task found
            return oldId;
          }
        }*/
            }
            final int newId = taskIdCounter.addAndGet(1);
            jsQueue.add(new ScheduledTask(newId, task));
            return newId;
            // }
        }

        public boolean hasPendingTasks() {
            return (!jsQueue.isEmpty()) || running.get();
        }
    }

    private URL getCurrURL() {
        try {
            return new URL(rcontext.getCurrentURL());
        }
        catch (MalformedURLException e) {
            return null;
        }
    }

    private volatile JSScheduler jsScheduler = new JSScheduler(this);

    @Override
    @HideFromJS
    public void addJSTask(final JSTask task) {
      /*
      final URL urlContext = new URL(rcontext.getCurrentURL());
      if (document != null) {
        final URL urlDoc = document.getDocumentURL();
        if (!urlDoc.equals(urlContext)) {
          throw new RuntimeException(String.format("doc url(%s) is different from context url (%s)", urlDoc, urlContext));
        }
      }*/
        final URL urlContext = getCurrURL();
        if (urlContext != null) {
            if (uaContext.isRequestPermitted(new Request(urlContext, RequestKind.JavaScript))) {
                // System.out.println("Adding task: " + task);
                synchronized (this) {
                    jsScheduler.addJSTask(task);
                }
            }
        }
        else {
            // TODO: This happens when the URL is not accepted by okhttp
            if (logger.isDebugEnabled()) {
                logger.debug("Not adding task because url context is null");
            }
        }
    }

    // TODO: Also look at GH #149
    // TODO: Try to refactor this so that all tasks are checked here rather than in caller
    // TODO: Some tasks are added unchecked for various reasons that need to be reviewed:
    //       1. Timer task. The logic is that a script that was permitted to create the timer already has the permission to execute it.
    //          But it would be better if their permission is checked again to account for subsequent changes through RequestManager,
    //          or if RequestManager assures that page is reloaded for *any* permission change.
    //       2. Event listeners. Logic is similar to Timer task
    //       3. Script elements. They are doing the checks themselves, but it would better to move the check here.
    //       4. XHR handler. Logic similar to timer task.
    @Override
    @HideFromJS
    public void addJSTaskUnchecked(final JSTask task) {
        // System.out.println("Adding task: " + task);
        synchronized (this) {
            jsScheduler.addJSTask(task);
        }
    }

    @Override
    @HideFromJS
    public int addJSUniqueTask(final int oldId, final JSTask task) {
        System.out.println("Adding unique task: " + task);

        synchronized (this) {
            return jsScheduler.addUniqueJSTask(oldId, task);
        }
    }

    private void putAndStartTask(final Integer timeoutID, final Timer timer, final Object retained) {
        TaskWrapper oldTaskWrapper = null;
        synchronized (this) {
            Map<Integer, TaskWrapper> taskMap = this.taskMap;
            if (taskMap == null) {
                taskMap = new HashMap<>(4);
                this.taskMap = taskMap;
            }
            else {
                oldTaskWrapper = taskMap.get(timeoutID);
            }
            taskMap.put(timeoutID, new TaskWrapper(timer, retained));
        }
        // Do this outside synchronized block, just in case.
        if (oldTaskWrapper != null) {
            oldTaskWrapper.timer.stop();
        }
        timer.start();
    }

    private void forgetTask(final Integer timeoutID, final boolean cancel) {
        TaskWrapper oldTimer = null;
        synchronized (this) {
            final Map<Integer, TaskWrapper> taskMap = this.taskMap;
            if (taskMap != null) {
                oldTimer = taskMap.remove(timeoutID);
            }
        }
        if ((oldTimer != null) && cancel) {
            oldTimer.timer.stop();
        }
    }

    private void forgetAllTasks() {
        TaskWrapper[] oldTaskWrappers = null;
        synchronized (this) {
            final Map<Integer, TaskWrapper> taskMap = this.taskMap;
            if (taskMap != null) {
                oldTaskWrappers = taskMap.values().toArray(new TaskWrapper[0]);
                this.taskMap = null;
            }
        }
        if (oldTaskWrappers != null) {
            for (final TaskWrapper taskWrapper : oldTaskWrappers) {
                taskWrapper.timer.stop();
            }
        }
    }

    // private Timer getTask(Long timeoutID) {
    // synchronized(this) {
    // Map taskMap = this.taskMap;
    // if(taskMap != null) {
    // return (Timer) taskMap.get(timeoutID);
    // }
    // }
    // return null;
    // }

    /**
     * @param aFunction Javascript function to invoke on each loop.
     * @param aTimeInMs Time in millisecund between each loop. TODO: Can this be converted
     *                  to long type?
     * @return Return the timer ID to use as reference
     * @todo Make proper and refactore with
     * {@link RhinoWindow#setTimeout(Function, double)}.
     * @see <a
     * href="http://developer.mozilla.org/en/docs/DOM:window.setInterval">Window.setInterval
     * interface definition</a>
     */
    public int setInterval(final Function aFunction, final double aTimeInMs) {
        if ((aTimeInMs > Integer.MAX_VALUE) || (aTimeInMs < 0)) {
            throw new IllegalArgumentException("Timeout value " + aTimeInMs + " is not supported.");
        }
        final int timeID = generateTimerID();
        System.out.println("Created interval timer: " + timeID);
        final Integer timeIDInt = new Integer(timeID);
        final ActionListener task = new FunctionTimerTask(this, timeIDInt, aFunction, false);
        int t = (int) aTimeInMs;
        if (t < 1) {
            t = 1;
        }
        final Timer timer = new Timer(t, task);
        timer.setRepeats(true); // The only difference with setTimeout
        this.putAndStartTask(timeIDInt, timer, aFunction);
        return timeID;
    }

    /**
     * @param aExpression Javascript expression to invoke on each loop.
     * @param aTimeInMs   Time in millisecund between each loop.
     * @return Return the timer ID to use as reference
     * @todo Make proper and refactore with
     * {@link RhinoWindow#setTimeout(String, double)}.
     * @see <a
     * href="http://developer.mozilla.org/en/docs/DOM:window.setInterval">Window.setInterval
     * interface definition</a>
     */
    public int setInterval(final String aExpression, final double aTimeInMs) {
        if ((aTimeInMs > Integer.MAX_VALUE) || (aTimeInMs < 0)) {
            throw new IllegalArgumentException("Timeout value " + aTimeInMs + " is not supported.");
        }
        final int timeID = generateTimerID();
        final Integer timeIDInt = new Integer(timeID);
        final ActionListener task = new ExpressionTimerTask(this, timeIDInt, aExpression, false);
        int t = (int) aTimeInMs;
        if (t < 1) {
            t = 1;
        }
        final Timer timer = new Timer(t, task);
        timer.setRepeats(false); // The only difference with setTimeout
        this.putAndStartTask(timeIDInt, timer, null);
        return timeID;
    }

    /**
     * @param aTimerID Timer ID to stop.
     * @see <a
     * href="http://developer.mozilla.org/en/docs/DOM:window.clearInterval">Window.clearInterval
     * interface Definition</a>
     */
    public void clearInterval(final int aTimerID) {
        final Integer key = new Integer(aTimerID);
        this.forgetTask(key, true);
    }

    public void clearInterval(final Object unused) {
        // Happens when jQuery calls this with a null parameter;
        // TODO: Check if there are other cases
        if (unused instanceof Integer) {
            final Integer id = (Integer) unused;
            clearInterval((int) id);
            return;
        }
        System.out.println("Clear interval : ignoring " + unused);
        // TODO: Should this be throwing an exception?
        // throw new UnsupportedOperationException();
    }

    public void alert(final String message) {
        final HtmlRendererContext rc = this.rcontext;
        if (rc != null) {
            rc.alert(message);
        }
    }

    public void back() {
        final HtmlRendererContext rc = this.rcontext;
        if (rc != null) {
            rc.back();
        }
    }

    public void blur() {
        final HtmlRendererContext rc = this.rcontext;
        if (rc != null) {
            rc.blur();
        }
    }

    public void clearTimeout(final Object someObj) {
        if (someObj instanceof Integer) {
            final Integer id = (Integer) someObj;
            clearTimeout(id.intValue());
        }
        else {
            System.out.println("Window.clearTimeout() : Ignoring: " + someObj);
        }
    }

    private void clearTimeout(final int timeoutID) {
        System.out.println("Clearing timeout: " + timeoutID);
        final Integer key = new Integer(timeoutID);
        this.forgetTask(key, true);
    }

    public void close() {
        final HtmlRendererContext rc = this.rcontext;
        if (rc != null) {
            rc.close();
        }
    }

    public boolean confirm(final String message) {
        final HtmlRendererContext rc = this.rcontext;
        if (rc != null) {
            return rc.confirm(message);
        }
        else {
            return false;
        }
    }

    // Making public for link element
    @HideFromJS
    public void evalInScope(final String javascript) {
        addJSTask(new JSRunnableTask(0, new Runnable() {
            @Override
            public void run() {
                try {
                    final String scriptURI = "window.eval";
                    final Context ctx = Executor.createContext(getCurrURL(), RhinoWindow.this.uaContext, windowContextFactory);
                    ctx.evaluateString(getWindowScope(), javascript, scriptURI, 1, null);
                }
                finally {
                    Context.exit();
                }
            }
        }));
    }

  /*
  private Object evalInScope(final String javascript) {
    final Context ctx = Executor.createContext(document.getDocumentURL(), this.uaContext);
    try {
      final String scriptURI = "window.eval";
      return ctx.evaluateString(getWindowScope(), javascript, scriptURI, 1, null);
    } finally {
      Context.exit();
    }
  }

  /* Removing because this eval method interferes with the default eval() method.
   * The context of the JS eval() call is not preserved by this method.
  public Object eval(final String javascript) {
    final HTMLDocumentImpl document = (HTMLDocumentImpl) this.document;
    if (document == null) {
      throw new IllegalStateException("Cannot evaluate if document is not set.");
    }
    final Context ctx = Executor.createContext(document.getDocumentURL(), this.uaContext);
    try {
      final Scriptable scope = this.getWindowScope();
      if (scope == null) {
        throw new IllegalStateException("Scriptable (scope) instance was expected to be keyed as UserData to document using "
            + Executor.SCOPE_KEY);
      }
      final String scriptURI = "window.eval";
      if (logger.isLoggable(Level.INFO)) {
        logger.info("eval(): javascript follows...\r\n" + javascript);
      }
      return ctx.evaluateString(scope, javascript, scriptURI, 1, null);
    } finally {
      Context.exit();
    }
  }
   */

    public void focus() {
        final HtmlRendererContext rc = this.rcontext;
        if (rc != null) {
            rc.focus();
        }
    }

    private class MyContextFactory extends ContextFactory {
        final private ClassShutter myClassShutter = new ClassShutter() {
            @Override
            public boolean visibleToScripts(final String fullClassName) {
                // System.out.println("class shutter Checking: " + fullClassName);
                if (fullClassName.startsWith("java")) {
                    final boolean isException = (fullClassName.startsWith("java.lang") && fullClassName.endsWith("Exception"));
                    if (fullClassName.equals("java.lang.Object") || isException) {
                        return true;
                    }
                    System.out.println("Warning: Something tried to access java classes from javascript.");
                    Thread.dumpStack();
                    return false;
                }

                // TODO: Change the default to false
                return true;
            }
        };

        // Override {@link #makeContext()}
        @Override
        protected Context makeContext() {
            final Context cx = super.makeContext();
            cx.setClassShutter(myClassShutter);
            // cx.setOptimizationLevel(9);
            cx.setOptimizationLevel(-1);
            cx.setLanguageVersion(Context.VERSION_1_8);

            // Make Rhino runtime to call observeInstructionCount each 100_000 bytecode instructions
            cx.setInstructionObserverThreshold(100_000);

            // cx.setMaximumInterpreterStackDepth(100);
            // cx.seal(null);

            return cx;
        }

        @Override
        protected void observeInstructionCount(final Context cx, final int instructionCount) {
            final JSScheduler jsSchedulerLocal = jsScheduler;
            if (jsSchedulerLocal != null) {
                if (jsSchedulerLocal.isWindowClosing()) {
                    throw new WindowClosingError();
                }
            }
        }

        @Override
        protected boolean hasFeature(Context cx, int featureIndex) {
            if (featureIndex == Context.FEATURE_V8_EXTENSIONS) {
                return true;
            }
            return super.hasFeature(cx, featureIndex);
        }
    }

    static final class WindowClosingError extends Error {
        private static final long serialVersionUID = 5375592396498284425L;
    }

    private final MyContextFactory windowContextFactory = new MyContextFactory();

    @HideFromJS
    public ContextFactory getContextFactory() {
        return windowContextFactory;
    }

    private void initWindowScope(final Document doc) {
        // Special Javascript class: XMLHttpRequest
        final Scriptable ws = this.getWindowScope();
        final JavaInstantiator xi = new JavaInstantiator() {
            @Override
            public Object newInstance(final Object[] args) {
                final Document d = doc;
                if (d == null) {
                    throw new IllegalStateException("Cannot perform operation when document is unset.");
                }
                HTMLDocumentImpl hd;
                try {
                    hd = (HTMLDocumentImpl) d;
                }
                catch (final ClassCastException err) {
                    throw new IllegalStateException("Cannot perform operation with documents of type " + d.getClass().getName() + ".");
                }
                return new XMLHttpRequest(uaContext, hd.getDocumentURL(), ws, RhinoWindow.this);
            }
        };
        defineInstantiator(ws, "XMLHttpRequest", XMLHTTPREQUEST_WRAPPER, xi);

        final JavaInstantiator pi = new JavaInstantiator() {
            @Override
            public Object newInstance(final Object[] args) {
                return new CanvasPath2D();
            }
        };
        defineInstantiator(ws, "Path2D", PATH2D_WRAPPER, pi);

        final JavaInstantiator ei = new JavaInstantiator() {
            @Override
            public Object newInstance(final Object[] args) {
                if (args.length > 0) {
                    return new RhinoEvent(args[0].toString(), doc);
                }
                throw ScriptRuntime.constructError("TypeError", "An event name must be provided");
            }
        };
        defineInstantiator(ws, "Event", EVENT_WRAPPER, ei);

        // We can use a single shared instance since it is dummy for now
        ScriptableObject.putProperty(ws, "localStorage", STORAGE);
        ScriptableObject.putProperty(ws, "sessionStorage", STORAGE);

        // ScriptableObject.defineClass(ws, org.mozilla.javascript.ast.Comment.class);
        defineElementClass(ws, doc, "Comment", "comment", CommentImpl.class);

        // HTML element classes
        defineElementClass(ws, doc, "Image", "img", HTMLImageElementImpl.class);
        defineElementClass(ws, doc, "Script", "script", HTMLScriptElementImpl.class);
        defineElementClass(ws, doc, "IFrame", "iframe", HTMLIFrameElementImpl.class);
        defineElementClass(ws, doc, "Option", "option", HTMLOptionElementImpl.class);
        defineElementClass(ws, doc, "Select", "select", HTMLSelectElementImpl.class);

        // TODO: Add all similar elements
        defineElementClass(ws, doc, "HTMLDivElement", "div", HTMLDivElementImpl.class);

        defineInstantiator(ws, "Text", JavaClassWrapperFactory.getInstance().getClassWrapper(TextImpl.class), new JavaInstantiator() {
            @Override
            public Object newInstance(final Object[] args) {
                final String data = (args != null && args.length > 0 && args[0] != null) ? args[0].toString() : "";
                return document.createTextNode(data);
            }
        });
    }

    private static void defineInstantiator(
        final Scriptable ws,
        final String name,
        final JavaClassWrapper wrapper,
        final JavaInstantiator ji) {

        final Function constructor = JavaObjectWrapper.getConstructor(name, wrapper, ws, ji);
        ScriptableObject.defineProperty(ws, name, constructor, ScriptableObject.READONLY);
    }

    private Scriptable windowScope;

    @HideFromJS
    public Scriptable getWindowScope() {
        synchronized (this) {
            Scriptable ws = this.windowScope;
            if (ws != null) {
                return ws;
            }
            // Context.enter() OK in this particular case.
            // final Context ctx = Context.enter();
            final Context ctx = windowContextFactory.enterContext();
            try {
                // Window scope needs to be top-most scope.
                ws = (Scriptable) RhinoJavaScript.getInstance().getJavascriptObject(this, null);
                ws = ctx.initSafeStandardObjects((ScriptableObject) ws);
                final Object consoleJSObj = RhinoJavaScript.getInstance().getJavascriptObject(new Console(), ws);
                ScriptableObject.putProperty(ws, "console", consoleJSObj);
                this.windowScope = ws;
                return ws;
            }
            finally {
                Context.exit();
            }
        }
    }

    static public class Console {
        public static void log(final Object obj) {
            System.out.println("> " + obj);
        }

    }

    private final static void defineElementClass(final Scriptable scope, final Document document, final String jsClassName,
                                                 final String elementName,
                                                 final Class<?> javaClass) {
        final JavaInstantiator ji = new JavaInstantiator() {
            @Override
            public Object newInstance(final Object[] args) {
                final Document d = document;
                if (d == null) {
                    throw new IllegalStateException("Document not set in current context.");
                }
                return d.createElement(elementName);
            }
        };
        final JavaClassWrapper classWrapper = JavaClassWrapperFactory.getInstance().getClassWrapper(javaClass);
        final Function constructorFunction = JavaObjectWrapper.getConstructor(jsClassName, classWrapper, scope, ji);
        ScriptableObject.defineProperty(scope, jsClassName, constructorFunction, ScriptableObject.READONLY);
    }

    @HideFromJS
    public static RhinoWindow getWindow(final HtmlRendererContext rcontext) {
        if (rcontext == null) {
            return null;
        }
        synchronized (CONTEXT_WINDOWS) {
            final WeakReference<RhinoWindow> wref = CONTEXT_WINDOWS.get(rcontext);
            if (wref != null) {
                final RhinoWindow window = wref.get();
                if (window != null) {
                    return window;
                }
            }
            final RhinoWindow window = new RhinoWindow(rcontext, rcontext.getUserAgentContext());
            CONTEXT_WINDOWS.put(rcontext, new WeakReference<>(window));
            return window;
        }
    }

    @Override
    public RhinoWindow open(final String relativeUrl, final String windowName, final String windowFeatures, final boolean replace) {
        final HtmlRendererContext rc = this.rcontext;
        if (rc != null) {
            URL url;
            final Document doc = this.document;
            try {
                if (doc instanceof HTMLDocumentImpl) {
                    url = ((HTMLDocumentImpl) doc).getFullURL(relativeUrl);
                }
                else {
                    url = new URL(relativeUrl);
                }
            }
            catch (final MalformedURLException mfu) {
                throw new IllegalArgumentException("Malformed URI: " + relativeUrl);
            }
            if (replace) {
                this.document = null;
                rc.navigate(url, null);
                return this;
            }
            else {
                final HtmlRendererContext newContext = rc.open(url, windowName, windowFeatures, replace);
                return getWindow(newContext);
            }
        }
        else {
            return null;
        }
    }

    @Override
    public RhinoWindow open(final String url) {
        return this.open(url, "window:" + String.valueOf(ID.generateLong()));
    }

    @Override
    public RhinoWindow open(final String url, final String windowName) {
        return this.open(url, windowName, "", false);
    }

    @Override
    public RhinoWindow open(final String url, final String windowName, final String windowFeatures) {
        return this.open(url, windowName, windowFeatures, false);
    }

    public String prompt(final String message) {
        return this.prompt(message, "");
    }

    public String prompt(final String message, final int inputDefault) {
        return this.prompt(message, String.valueOf(inputDefault));
    }

    public String prompt(final String message, final String inputDefault) {
        final HtmlRendererContext rcontext = this.rcontext;
        if (rcontext != null) {
            return rcontext.prompt(message, inputDefault);
        }
        else {
            return null;
        }
    }

    public void scrollTo(final int x, final int y) {
        final HtmlRendererContext rc = this.rcontext;
        if (rc != null) {
            rc.scroll(x, y);
        }
    }

    public void scrollBy(final int x, final int y) {
        final HtmlRendererContext rc = this.rcontext;
        if (rc != null) {
            rc.scrollBy(x, y);
        }
    }

    public void resizeTo(final int width, final int height) {
        final HtmlRendererContext rc = this.rcontext;
        if (rc != null) {
            rc.resizeTo(width, height);
        }
    }

    public void resizeBy(final int byWidth, final int byHeight) {
        final HtmlRendererContext rc = this.rcontext;
        if (rc != null) {
            rc.resizeBy(byWidth, byHeight);
        }
    }

    @NotGetterSetter
    public int setTimeout(final String expr, final double millis) {
        if ((millis > Integer.MAX_VALUE) || (millis < 0)) {
            throw new IllegalArgumentException("Timeout value " + millis + " is not supported.");
        }
        final int timeID = generateTimerID();
        final Integer timeIDInt = new Integer(timeID);
        final ActionListener task = new ExpressionTimerTask(this, timeIDInt, expr, true);
        int t = (int) millis;
        if (t < 1) {
            t = 1;
        }
        final Timer timer = new Timer(t, task);
        timer.setRepeats(false);
        this.putAndStartTask(timeIDInt, timer, null);
        return timeID;
    }

    @NotGetterSetter
    public int setTimeout(final Function function, final double millis) {
        if ((millis > Integer.MAX_VALUE) || (millis < 0)) {
            throw new IllegalArgumentException("Timeout value " + millis + " is not supported.");
        }
        final int timeID = generateTimerID();
        System.out.println("Creating timer with id: " + timeID + " in " + document.getBaseURI());
        final Integer timeIDInt = new Integer(timeID);
        final ActionListener task = new FunctionTimerTask(this, timeIDInt, function, true);
        int t = (int) millis;
        if (t < 1) {
            t = 1;
        }
        final Timer timer = new Timer(t, task);
        timer.setRepeats(false);
        this.putAndStartTask(timeIDInt, timer, function);
        return timeID;
    }

    @NotGetterSetter
    public int setTimeout(final Function function) {
        return setTimeout(function, 0);
    }

    @NotGetterSetter
    public int setTimeout(final String expr) {
        return setTimeout(expr, 0);
    }

    public boolean isClosed() {
        final HtmlRendererContext rc = this.rcontext;
        if (rc != null) {
            return rc.isClosed();
        }
        else {
            return false;
        }
    }

    public String getDefaultStatus() {
        final HtmlRendererContext rc = this.rcontext;
        if (rc != null) {
            return rc.getDefaultStatus();
        }
        else {
            return null;
        }
    }

    public HTMLCollection getFrames() {
        final Document doc = this.document;
        if (doc instanceof HTMLDocumentImpl) {
            return ((HTMLDocumentImpl) doc).getFrames();
        }
        return null;
    }

    private int length;
    private boolean lengthSet = false;

    /**
     * Gets the number of frames.
     */
    public int getLength() {
        if (this.lengthSet) {
            return this.length;
        }
        else {
            final HTMLCollection frames = this.getFrames();
            return frames == null ? 0 : frames.getLength();
        }
    }

    public void setLength(final int length) {
        this.lengthSet = true;
        this.length = length;
    }

    public String getName() {
        final HtmlRendererContext rc = this.rcontext;
        if (rc != null) {
            return rc.getName();
        }
        else {
            return null;
        }
    }

    public void setName(final String newName) {
        // TODO
        System.out.println("TODO: window.setName");
    }

    public RhinoWindow getParent() {
        final HtmlRendererContext rc = this.rcontext;
        if (rc != null) {
            final HtmlRendererContext rcontextParent = rc.getParent();
            if (rcontextParent == null) {
                return this;
            }
            else {
                return RhinoWindow.getWindow(rcontextParent);
            }
        }
        else {
            return null;
        }
    }

    public RhinoWindow getOpener() {
        final HtmlRendererContext rc = this.rcontext;
        if (rc != null) {
            return RhinoWindow.getWindow(rc.getOpener());
        }
        else {
            return null;
        }
    }

    public void setOpener(final RhinoWindow opener) {
        final HtmlRendererContext rc = this.rcontext;
        if (rc != null) {
            if (opener == null) {
                rc.setOpener(null);
            }
            else {
                rc.setOpener(opener.rcontext);
            }
        }
    }

    public RhinoWindow getSelf() {
        return this;
    }

    public String getStatus() {
        final HtmlRendererContext rc = this.rcontext;
        if (rc != null) {
            return rc.getStatus();
        }
        else {
            return null;
        }
    }

    public void setStatus(final String message) {
        final HtmlRendererContext rc = this.rcontext;
        if (rc != null) {
            rc.setStatus(message);
        }
    }

    public RhinoWindow getTop() {
        final HtmlRendererContext rc = this.rcontext;
        if (rc != null) {
            return RhinoWindow.getWindow(rc.getTop());
        }
        else {
            return null;
        }
    }

    public RhinoWindow getWindow() {
        return this;
    }

    public Navigator getNavigator() {
        synchronized (this) {
            Navigator nav = this.navigator;
            if (nav == null) {
                nav = new Navigator(this.uaContext);
                this.navigator = nav;
            }
            return nav;
        }
    }

    public RhinoScreen getScreen() {
        synchronized (this) {
            RhinoScreen nav = this.screen;
            if (nav == null) {
                nav = new RhinoScreen();
                this.screen = nav;
            }
            return nav;
        }
    }

    @Override
    public RhinoLocation getLocation() {
        synchronized (this) {
            RhinoLocation loc = this.location;
            if (loc == null) {
                loc = new RhinoLocation(this);
                this.location = loc;
            }
            return loc;
        }
    }

    public void setLocation(final String location) {
        this.getLocation().setHref(location);
    }

    private RhinoHistory history;

    public RhinoHistory getHistory() {
        synchronized (this) {
            RhinoHistory hist = this.history;
            if (hist == null) {
                hist = new RhinoHistory(this);
                this.history = hist;
            }
            return hist;
        }
    }

    public CSS2Properties getComputedStyle(final HTMLElement element, final String pseudoElement) {
        if (element instanceof HTMLElementImpl) {
            return ((HTMLElementImpl) element).getComputedStyle(pseudoElement);
        }
        else {
            throw new IllegalArgumentException("Element implementation unknown: " + element);
        }
    }

    public Object getOnload() {
        final Document doc = this.document;
        if (doc instanceof HTMLDocumentImpl) {
            return ((HTMLDocumentImpl) doc).getOnloadHandler();
        }
        else {
            return null;
        }
    }

    public void setOnload(final Function onload) {
        // Note that body.onload overrides
        // window.onload.
    /*
    final Document doc = this.document;
    if (doc instanceof HTMLDocumentImpl) {
      ((HTMLDocumentImpl) doc).setWindowOnloadHandler(onload);
    }*/
        onWindowLoadHandler = onload;
    }

    private Function onunload;

    public Function getOnunload() {
        return onunload;
    }

    public void setOnunload(final Function onunload) {
        this.onunload = onunload;
    }

    public Node namedItem(final String name) {
        // Bug 1928758: Element IDs are named objects in context.
        final Document doc = this.document;
        if (doc == null) {
            return null;
        }
        final Node node = doc.getElementById(name);
        if (node != null) {
            return node;
        }
        return null;
    }

    private static abstract class WeakWindowTask implements ActionListener {
        private final WeakReference<RhinoWindow> windowRef;

        public WeakWindowTask(final RhinoWindow window) {
            this.windowRef = new WeakReference<>(window);
        }

        protected RhinoWindow getWindow() {
            final WeakReference<RhinoWindow> ref = this.windowRef;
            return ref == null ? null : ref.get();
        }
    }

    private static class FunctionTimerTask extends WeakWindowTask {
        // Implemented as a static WeakWindowTask to allow the Window
        // to get garbage collected, especially in infinite loop
        // scenarios.
        private final Integer timeIDInt;
        private final WeakReference<Function> functionRef;
        private final boolean removeTask;

        public FunctionTimerTask(final RhinoWindow window, final Integer timeIDInt, final Function function, final boolean removeTask) {
            super(window);
            this.timeIDInt = timeIDInt;
            this.functionRef = new WeakReference<>(function);
            this.removeTask = removeTask;
        }

        @Override
        public void actionPerformed(final ActionEvent e) {
            System.out.println("Timer ID fired: " + timeIDInt + ", oneshot: " + removeTask);
            // This executes in the GUI thread and that's good.
            try {
                final RhinoWindow window = this.getWindow();
                if (window == null) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("actionPerformed(): Window is no longer available.");
                    }
                    return;
                }
                if (this.removeTask) {
                    window.forgetTask(this.timeIDInt, false);
                }
                // final HTMLDocumentImpl doc = (HTMLDocumentImpl) window.getDocument();
                if (window.getDocument() == null) {
                    throw new IllegalStateException("Cannot perform operation when document is unset.");
                }
                final Function function = this.functionRef.get();
                if (function == null) {
                    throw new IllegalStateException("Cannot perform operation. Function is no longer available.");
                }
                window.addJSTaskUnchecked(new JSRunnableTask(0, "timer task for id: " + timeIDInt + ", oneshot: " + removeTask, () -> {
                    Executor.executeFunction(window.getWindowScope(), function, window.getCurrURL(), window.getUserAgentContext(),
                        window.windowContextFactory);
                }));
                // Executor.executeFunction(window.getWindowScope(), function, doc.getDocumentURL(), window.getUserAgentContext(), window.windowFactory);
            }
            catch (final Exception err) {
                logger.warn("actionPerformed()", err);
            }
        }
    }

    private static class ExpressionTimerTask extends WeakWindowTask {
        // Implemented as a static WeakWindowTask to allow the Window
        // to get garbage collected, especially in infinite loop
        // scenarios.
        private final Integer timeIDInt;
        private final String expression;
        private final boolean removeTask;

        public ExpressionTimerTask(final RhinoWindow window, final Integer timeIDInt, final String expression, final boolean removeTask) {
            super(window);
            this.timeIDInt = timeIDInt;
            this.expression = expression;
            this.removeTask = removeTask;
        }

        @Override
        public void actionPerformed(final ActionEvent e) {
            // This executes in the GUI thread and that's good.
            try {
                final RhinoWindow window = this.getWindow();
                if (window == null) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("actionPerformed(): Window is no longer available.");
                    }
                    return;
                }
                if (this.removeTask) {
                    window.forgetTask(this.timeIDInt, false);
                }
                // final HTMLDocumentImpl doc = (HTMLDocumentImpl) window.getDocument();
                if (window.getDocument() == null) {
                    throw new IllegalStateException("Cannot perform operation when document is unset.");
                }
                window.addJSTaskUnchecked(new JSRunnableTask(0, "timer task for id: " + timeIDInt, () -> {
                    window.evalInScope(this.expression);
                }));
                // window.evalInScope(this.expression);
            }
            catch (final Exception err) {
                logger.warn("actionPerformed()", err);
            }
        }
    }

    private static class TaskWrapper {
        public final Timer timer;
        // TODO: The retained object seems to be required to keep timer callback functions from being garbage collected.
        //       The FunctionTimerTask only keeps a weak reference. Need to review this design.
        @SuppressWarnings("unused")
        private final Object retained;

        public TaskWrapper(final Timer timer, final Object retained) {
            super();
            this.timer = timer;
            this.retained = retained;
        }
    }

    public void addEventListener(final String type, final Object listener) {
        addEventListener(type, listener, false);
    }

    private final RhinoEventTargetManager eventTargetManager = new RhinoEventTargetManager(this);

    @Override
    public EventTargetManager getEventTargetManager() {
        return eventTargetManager;
    }

    public void addEventListener(final String type, final Object listener, final boolean useCapture) {
        if (useCapture) {
            throw new UnsupportedOperationException();
        }
    /*
    // TODO: Should this delegate completely to document
    if ("load".equals(type)) {
      document.addLoadHandler(listener);
    } else {
      document.addEventListener(type, listener);
    }*/
        System.out.println("window Added listener for: " + type);
        eventTargetManager.addEventListener((NodeImpl) document, type, listener);
    }

    public void removeEventListener(final String type, final Object listener, final boolean useCapture) {
        // TODO: Should this delegate completely to document
        if ("load".equals(type)) {
            ((HTMLDocumentImpl) document).removeLoadHandler(listener);
        }
        eventTargetManager.removeEventListener((NodeImpl) document, type, listener, useCapture);
    }

    public boolean dispatchEvent(final RhinoEvent evt) throws EventException {
        // TODO
        eventTargetManager.dispatchEvent((NodeImpl) document, evt);
        return false;
    }

    // TODO: Hide from JS
    @Override
    public void domContentLoaded(final Event domContentLoadedEvent) {
        eventTargetManager.dispatchEvent((NodeImpl) document, domContentLoadedEvent);
    }

    private Function onWindowLoadHandler;

    // private Function windowLoadListeners;

    // TODO: Move job scheduling logic into Window class
    private AtomicBoolean jobsOver = new AtomicBoolean(false);

    @Override
    @HideFromJS
    public void jobsFinished() {
        final RhinoEvent windowLoadEvent = new RhinoEvent("load", document);
        eventTargetManager.dispatchEvent((NodeImpl) document, windowLoadEvent);

        final Function handler = this.onWindowLoadHandler;
        if (handler != null) {
            addJSTask(new JSRunnableTask(0, new Runnable() {
                @Override
                public void run() {
                    Executor.executeFunction((NodeImpl) document, handler, windowLoadEvent, windowContextFactory);
                }
            }));
            // Executor.executeFunction(document, handler, windowLoadEvent);
        }

        jobsOver.set(true);
    }

    @PropertyName("Element")
    public Class<Element> getElement() {
        return Element.class;
    }

    @PropertyName("Node")
    public Class<Node> getNode() {

        return Node.class;
    }

    public void addEventListener(final String type, final EventListener listener) {
        addEventListener(type, listener, false);
    }

    @Override
    public void addEventListener(final String type, final EventListener listener, final boolean useCapture) {
        if (useCapture) {
            throw new UnsupportedOperationException();
        }
        // TODO Auto-generated method stub
        // throw new UnsupportedOperationException();
        eventTargetManager.addEventListener((NodeImpl) document, type, listener, useCapture);
    }

    @Override
    public void removeEventListener(final String type, final EventListener listener, final boolean useCapture) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean dispatchEvent(final org.w3c.dom.events.Event evt) throws EventException {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException();
    }

    private void shutdown() {
        // TODO: Add the sync below, when/if the scheduleLock is added
        // synchronized (scheduleLock) {
        forgetAllTasks();

        if (jsScheduler != null) {
            AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
                jsScheduler.stopAndWindUp(false);
                jsScheduler = null;
                return null;
            });
        }
        // }
    }

    // TODO: More thorough research and design needs to be done here. GH-127
    @Override
    protected void finalize() throws Throwable {
        shutdown();
        super.finalize();
    }

    @Override
    @HideFromJS
    public boolean hasPendingTasks() {
        return (!jobsOver.get()) || jsScheduler.hasPendingTasks();
    }
}

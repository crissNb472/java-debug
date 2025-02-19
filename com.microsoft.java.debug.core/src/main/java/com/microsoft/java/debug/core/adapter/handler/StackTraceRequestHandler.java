/*******************************************************************************
* Copyright (c) 2017-2022 Microsoft Corporation and others.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Eclipse Public License v1.0
* which accompanies this distribution, and is available at
* http://www.eclipse.org/legal/epl-v10.html
*
* Contributors:
*     Microsoft Corporation - initial API and implementation
*******************************************************************************/

package com.microsoft.java.debug.core.adapter.handler;

import java.io.File;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

import com.microsoft.java.debug.core.AsyncJdwpUtils;
import com.microsoft.java.debug.core.DebugUtility;
import com.microsoft.java.debug.core.adapter.AdapterUtils;
import com.microsoft.java.debug.core.adapter.IDebugAdapterContext;
import com.microsoft.java.debug.core.adapter.IDebugRequestHandler;
import com.microsoft.java.debug.core.adapter.ISourceLookUpProvider;
import com.microsoft.java.debug.core.adapter.formatter.SimpleTypeFormatter;
import com.microsoft.java.debug.core.adapter.variables.StackFrameReference;
import com.microsoft.java.debug.core.protocol.Messages.Response;
import com.microsoft.java.debug.core.protocol.Requests.Arguments;
import com.microsoft.java.debug.core.protocol.Requests.Command;
import com.microsoft.java.debug.core.protocol.Requests.StackTraceArguments;
import com.microsoft.java.debug.core.protocol.Responses;
import com.microsoft.java.debug.core.protocol.Types;
import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectCollectedException;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.ThreadReference;

public class StackTraceRequestHandler implements IDebugRequestHandler {

    @Override
    public List<Command> getTargetCommands() {
        return Arrays.asList(Command.STACKTRACE);
    }

    @Override
    public CompletableFuture<Response> handle(Command command, Arguments arguments, Response response, IDebugAdapterContext context) {
        StackTraceArguments stacktraceArgs = (StackTraceArguments) arguments;
        List<Types.StackFrame> result = new ArrayList<>();
        if (stacktraceArgs.startFrame < 0 || stacktraceArgs.levels < 0) {
            response.body = new Responses.StackTraceResponseBody(result, 0);
            return CompletableFuture.completedFuture(response);
        }
        ThreadReference thread = context.getThreadCache().getThread(stacktraceArgs.threadId);
        if (thread == null) {
            thread = DebugUtility.getThread(context.getDebugSession(), stacktraceArgs.threadId);
        }
        int totalFrames = 0;
        if (thread != null) {
            try {
                // Thread state has changed and then invalidate the stack frame cache.
                if (stacktraceArgs.startFrame == 0) {
                    context.getStackFrameManager().clearStackFrames(thread);
                }

                totalFrames = thread.frameCount();
                int count = stacktraceArgs.levels == 0 ? totalFrames - stacktraceArgs.startFrame
                        : Math.min(totalFrames - stacktraceArgs.startFrame, stacktraceArgs.levels);
                if (totalFrames <= stacktraceArgs.startFrame) {
                    response.body = new Responses.StackTraceResponseBody(result, totalFrames);
                    return CompletableFuture.completedFuture(response);
                }

                StackFrame[] frames = context.getStackFrameManager().reloadStackFrames(thread, stacktraceArgs.startFrame, count);
                List<StackFrameInfo> jdiFrames = resolveStackFrameInfos(frames, context.asyncJDWP());
                for (int i = 0; i < count; i++) {
                    StackFrameReference stackframe = new StackFrameReference(thread, stacktraceArgs.startFrame + i);
                    int frameId = context.getRecyclableIdPool().addObject(stacktraceArgs.threadId, stackframe);
                    StackFrameInfo jdiFrame = jdiFrames.get(i);
                    result.add(convertDebuggerStackFrameToClient(jdiFrame, frameId, context));
                }
            } catch (IncompatibleThreadStateException | IndexOutOfBoundsException | URISyntaxException
                    | AbsentInformationException | ObjectCollectedException
                    | CancellationException | CompletionException e) {
                // when error happens, the possible reason is:
                // 1. the vscode has wrong parameter/wrong uri
                // 2. the thread actually terminates
                // TODO: should record a error log here.
            }
        }
        response.body = new Responses.StackTraceResponseBody(result, totalFrames);
        return CompletableFuture.completedFuture(response);
    }

    private static List<StackFrameInfo> resolveStackFrameInfos(StackFrame[] frames, boolean async)
        throws AbsentInformationException, IncompatibleThreadStateException {
        List<StackFrameInfo> jdiFrames = new ArrayList<>();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (StackFrame frame : frames) {
            StackFrameInfo jdiFrame = new StackFrameInfo(frame);
            jdiFrame.location = jdiFrame.frame.location();
            jdiFrame.method = jdiFrame.location.method();
            jdiFrame.methodName = jdiFrame.method.name();
            jdiFrame.isNative = jdiFrame.method.isNative();
            jdiFrame.declaringType = jdiFrame.location.declaringType();
            if (async) {
                // JDWP Command: M_LINE_TABLE
                futures.add(AsyncJdwpUtils.runAsync(() -> {
                    jdiFrame.lineNumber = jdiFrame.location.lineNumber();
                }));

                // JDWP Commands: RT_SOURCE_DEBUG_EXTENSION, RT_SOURCE_FILE
                futures.add(AsyncJdwpUtils.runAsync(() -> {
                    try {
                        // When the .class file doesn't contain source information in meta data,
                        // invoking Location#sourceName() would throw AbsentInformationException.
                        jdiFrame.sourceName = jdiFrame.declaringType.sourceName();
                    } catch (AbsentInformationException e) {
                        jdiFrame.sourceName = null;
                    }
                }));

                // JDWP Command: RT_SIGNATURE
                futures.add(AsyncJdwpUtils.runAsync(() -> {
                    jdiFrame.typeSignature = jdiFrame.declaringType.signature();
                }));
            } else {
                jdiFrame.lineNumber = jdiFrame.location.lineNumber();
                jdiFrame.typeSignature = jdiFrame.declaringType.signature();
                try {
                    // When the .class file doesn't contain source information in meta data,
                    // invoking Location#sourceName() would throw AbsentInformationException.
                    jdiFrame.sourceName = jdiFrame.declaringType.sourceName();
                } catch (AbsentInformationException e) {
                    jdiFrame.sourceName = null;
                }
            }

            jdiFrames.add(jdiFrame);
        }

        AsyncJdwpUtils.await(futures);
        for (StackFrameInfo jdiFrame : jdiFrames) {
            jdiFrame.typeName = jdiFrame.declaringType.name();
            jdiFrame.argumentTypeNames = jdiFrame.method.argumentTypeNames();
            if (jdiFrame.sourceName == null) {
                String enclosingType = AdapterUtils.parseEnclosingType(jdiFrame.typeName);
                jdiFrame.sourceName = enclosingType.substring(enclosingType.lastIndexOf('.') + 1) + ".java";
                jdiFrame.sourcePath = enclosingType.replace('.', File.separatorChar) + ".java";
            } else {
                jdiFrame.sourcePath = jdiFrame.declaringType.sourcePaths(null).get(0);
            }
        }

        return jdiFrames;
    }

    private Types.StackFrame convertDebuggerStackFrameToClient(StackFrameInfo jdiFrame, int frameId, IDebugAdapterContext context)
            throws URISyntaxException, AbsentInformationException {
        Types.Source clientSource = convertDebuggerSourceToClient(jdiFrame.typeName, jdiFrame.sourceName, jdiFrame.sourcePath, context);
        String methodName = formatMethodName(jdiFrame.methodName, jdiFrame.argumentTypeNames, jdiFrame.typeName, true, true);
        int clientLineNumber = AdapterUtils.convertLineNumber(jdiFrame.lineNumber, context.isDebuggerLinesStartAt1(), context.isClientLinesStartAt1());
        // Line number returns -1 if the information is not available; specifically, always returns -1 for native methods.
        String presentationHint = null;
        if (clientLineNumber < 0) {
            presentationHint = "subtle";
            if (jdiFrame.isNative) {
                // For native method, display a tip text "native method" in the Call Stack View.
                methodName += "[native method]";
            } else {
                // For other unavailable method, such as lambda expression's built-in methods run/accept/apply,
                // display "Unknown Source" in the Call Stack View.
                clientSource = null;
            }
        }
        return new Types.StackFrame(frameId, methodName, clientSource, clientLineNumber, context.isClientColumnsStartAt1() ? 1 : 0, presentationHint);
    }

    /**
     * Find the source mapping for the specified source file name.
     */
    public static Types.Source convertDebuggerSourceToClient(String fullyQualifiedName, String sourceName, String relativeSourcePath,
            IDebugAdapterContext context) throws URISyntaxException {
        // use a lru cache for better performance
        String uri = context.getSourceLookupCache().computeIfAbsent(fullyQualifiedName, key -> {
            String fromProvider = context.getProvider(ISourceLookUpProvider.class).getSourceFileURI(key, relativeSourcePath);
            // avoid return null which will cause the compute function executed again
            return StringUtils.isBlank(fromProvider) ? "" : fromProvider;
        });

        if (!StringUtils.isBlank(uri)) {
            // The Source.path could be a file system path or uri string.
            if (uri.startsWith("file:")) {
                String clientPath = AdapterUtils.convertPath(uri, context.isDebuggerPathsAreUri(), context.isClientPathsAreUri());
                return new Types.Source(sourceName, clientPath, 0);
            } else {
                // If the debugger returns uri in the Source.path for the StackTrace response, VSCode client will try to find a TextDocumentContentProvider
                // to render the contents.
                // Language Support for Java by Red Hat extension has already registered a jdt TextDocumentContentProvider to parse the jdt-based uri.
                // The jdt uri looks like 'jdt://contents/rt.jar/java.io/PrintStream.class?=1.helloworld/%5C/usr%5C/lib%5C/jvm%5C/java-8-oracle%5C/jre%5C/
                // lib%5C/rt.jar%3Cjava.io(PrintStream.class'.
                return new Types.Source(sourceName, uri, 0);
            }
        } else {
            // If the source lookup engine cannot find the source file, then lookup it in the source directories specified by user.
            String absoluteSourcepath = AdapterUtils.sourceLookup(context.getSourcePaths(), relativeSourcePath);
            if (absoluteSourcepath != null) {
                return new Types.Source(sourceName, absoluteSourcepath, 0);
            } else {
                return null;
            }
        }
    }

    private String formatMethodName(String methodName, List<String> argumentTypeNames, String fqn, boolean showContextClass, boolean showParameter) {
        StringBuilder formattedName = new StringBuilder();
        if (showContextClass) {
            formattedName.append(SimpleTypeFormatter.trimTypeName(fqn));
            formattedName.append(".");
        }
        formattedName.append(methodName);
        if (showParameter) {
            argumentTypeNames = argumentTypeNames.stream().map(SimpleTypeFormatter::trimTypeName).collect(Collectors.toList());
            formattedName.append("(");
            formattedName.append(String.join(",", argumentTypeNames));
            formattedName.append(")");
        }
        return formattedName.toString();
    }

    static class StackFrameInfo {
        public StackFrame frame;
        public Location location;
        public Method method;
        public String methodName;
        public List<String> argumentTypeNames = new ArrayList<>();
        public boolean isNative = false;
        public int lineNumber;
        public ReferenceType declaringType = null;
        public String typeName;
        public String typeSignature;
        public String sourceName = "";
        public String sourcePath = "";

        // variables
        public List<LocalVariable> visibleVariables = null;
        public ObjectReference thisObject;

        public StackFrameInfo(StackFrame frame) {
            this.frame = frame;
        }
    }
}

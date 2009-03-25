/*
 * Copyright (C) 2009 The Android Open Source Project
 *
 * Licensed under the Eclipse Public License, Version 1.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.eclipse.org/org/documents/epl-v10.php
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.ide.eclipse.adt.refactorings.extractstring;

import com.android.ide.eclipse.common.AndroidConstants;
import com.android.ide.eclipse.common.project.AndroidManifestParser;
import com.android.ide.eclipse.common.project.AndroidXPathFactory;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourceAttributes;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.Path;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jdt.core.IBuffer;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.compiler.IScanner;
import org.eclipse.jdt.core.compiler.ITerminalSymbols;
import org.eclipse.jdt.core.compiler.InvalidInputException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ImportRewrite;
import org.eclipse.jface.text.ITextSelection;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.ChangeDescriptor;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.Refactoring;
import org.eclipse.ltk.core.refactoring.RefactoringChangeDescriptor;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.TextFileChange;
import org.eclipse.text.edits.InsertEdit;
import org.eclipse.text.edits.MultiTextEdit;
import org.eclipse.text.edits.ReplaceEdit;
import org.eclipse.text.edits.TextEdit;
import org.eclipse.text.edits.TextEditGroup;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;

/**
 * This refactoring extracts a string from a file and replaces it by an Android resource ID
 * such as R.string.foo.
 * <p/>
 * There are a number of scenarios, which are not all supported yet. The workflow works as
 * such:
 * <ul>
 * <li> User selects a string in a Java (TODO: or XML file) and invokes
 *      the {@link ExtractStringAction}.
 * <li> The action finds the {@link ICompilationUnit} being edited as well as the current
 *      {@link ITextSelection}. The action creates a new instance of this refactoring as
 *      well as an {@link ExtractStringWizard} and runs the operation.
 * <li> TODO: to support refactoring from an XML file, the action should give the {@link IFile}
 *      and then here we would have to determine whether it's a suitable Android XML file or a
 *      suitable Java file.
 *      TODO: enumerate the exact valid contexts in Android XML files, e.g. attributes in layout
 *      files or text elements (e.g. <string>foo</string>) for values, etc. 
 * <li> Step 1 of the refactoring is to check the preliminary conditions. Right now we check
 *      that the java source is not read-only and is in sync. We also try to find a string under
 *      the selection. If this fails, the refactoring is aborted.
 * <li> TODO: Find the string in an XML file based on selection.
 * <li> On success, the wizard is shown, which let the user input the new ID to use.
 * <li> The wizard sets the user input values into this refactoring instance, e.g. the new string
 *      ID, the XML file to update, etc. The wizard does use the utility method
 *      {@link #isResIdDuplicate(String, String)} to check whether the new ID is already defined
 *      in the target XML file.
 * <li> Once Preview or Finish is selected in the wizard, the
 *      {@link #checkFinalConditions(IProgressMonitor)} is called to double-check the user input
 *      and compute the actual changes.
 * <li> When all changes are computed, {@link #createChange(IProgressMonitor)} is invoked.
 * </ul>
 * 
 * The list of changes are:
 * <ul>
 * <li> If the target XML does not exist, create it with the new string ID.
 * <li> If the target XML exists, find the <resources> node and add the new string ID right after.
 *      If the node is <resources/>, it needs to be opened.
 * <li> Create an AST rewriter to edit the source Java file and replace all occurences by the
 *      new computed R.string.foo. Also need to rewrite imports to import R as needed.
 *      If there's already a conflicting R included, we need to insert the FQCN instead.
 * <li> TODO: If the source is an XML file, determine if we need to change an attribute or a
 *      a text element.
 * <li> TODO: Have a pref in the wizard: [x] Change other XML Files
 * <li> TODO: Have a pref in the wizard: [x] Change other Java Files
 * </ul>
 */
class ExtractStringRefactoring extends Refactoring {

    /** The file model being manipulated. */
    private final IFile mFile;
    /** The start of the selection in {@link #mFile}. */
    private final int mSelectionStart;
    /** The end of the selection in {@link #mFile}. */
    private final int mSelectionEnd;

    /** The compilation unit, only defined if {@link #mFile} points to a usable Java source file. */
    private ICompilationUnit mUnit;
    /** The actual string selected, after UTF characters have been escaped, good for display. */
    private String mTokenString;

    /** The XML string ID selected by the user in the wizard. */
    private String mXmlStringId;
    /** The path of the XML file that will define {@link #mXmlStringId}, selected by the user
     *  in the wizard. */
    private String mTargetXmlFileWsPath;

    /** A temporary cache of R.string IDs defined by a given xml file. The key is the
     * project path of the file, the data is a set of known string Ids for that file. */
    private HashMap<String,HashSet<String>> mResIdCache;
    /** An instance of XPath, created lazily on demand. */
    private XPath mXPath;
    /** The list of changes computed by {@link #checkFinalConditions(IProgressMonitor)} and
     *  used by {@link #createChange(IProgressMonitor)}. */
    private ArrayList<Change> mChanges;

    public ExtractStringRefactoring(Map<String, String> arguments)
            throws NullPointerException {

        IPath path = Path.fromPortableString(arguments.get("file"));        //$NON-NLS-1$
        mFile = (IFile) ResourcesPlugin.getWorkspace().getRoot().findMember(path);
        mSelectionStart = Integer.parseInt(arguments.get("sel-start"));     //$NON-NLS-1$
        mSelectionEnd   = Integer.parseInt(arguments.get("sel-end"));       //$NON-NLS-1$
        mTokenString    = arguments.get("tok-esc");                         //$NON-NLS-1$
    }
    
    private Map<String, String> createArgumentMap() {
        HashMap<String, String> args = new HashMap<String, String>();
        args.put("file",      mFile.getFullPath().toPortableString());      //$NON-NLS-1$
        args.put("sel-start", Integer.toString(mSelectionStart));           //$NON-NLS-1$
        args.put("sel-end",   Integer.toString(mSelectionEnd));             //$NON-NLS-1$
        args.put("tok-esc",   mTokenString);                                //$NON-NLS-1$
        return args;
    }

    public ExtractStringRefactoring(IFile file, ITextSelection selection) {
        mFile = file;
        mSelectionStart = selection.getOffset();
        mSelectionEnd = mSelectionStart + Math.max(0, selection.getLength() - 1);
    }

    /**
     * @see org.eclipse.ltk.core.refactoring.Refactoring#getName()
     */
    @Override
    public String getName() {
        return "Extract Android String";
    }
    
    /**
     * Gets the actual string selected, after UTF characters have been escaped,
     * good for display.
     */
    public String getTokenString() {
        return mTokenString;
    }
    
    /**
     * Step 1 of 3 of the refactoring:
     * Checks that the current selection meets the initial condition before the ExtractString
     * wizard is shown. The check is supposed to be lightweight and quick. Note that at that
     * point the wizard has not been created yet.
     * <p/>
     * Here we scan the source buffer to find the token matching the selection.
     * The check is successful is a Java string literal is selected, the source is in sync
     * and is not read-only.
     * <p/>
     * This is also used to extract the string to be modified, so that we can display it in
     * the refactoring wizard.
     * 
     * @see org.eclipse.ltk.core.refactoring.Refactoring#checkInitialConditions(org.eclipse.core.runtime.IProgressMonitor)
     * 
     * @throws CoreException 
     */
    @Override
    public RefactoringStatus checkInitialConditions(IProgressMonitor monitor)
            throws CoreException, OperationCanceledException {

        mUnit = null;
        mTokenString = null;

        RefactoringStatus status = new RefactoringStatus();
        
        try {
            monitor.beginTask("Checking preconditions...", 5);
            
            if (!checkSourceFile(mFile, status, monitor)) {
                return status;
            }

            // Try to get a compilation unit from this file. If it fails, mUnit is null.
            try {
                mUnit = JavaCore.createCompilationUnitFrom(mFile);

                // Make sure the unit is not read-only, e.g. it's not a class file or inside a Jar
                if (mUnit.isReadOnly()) {
                    status.addFatalError("The file is read-only, please make it writeable first.");
                    return status;
                }
                
                // This is a Java file. Check if it contains the selection we want.
                if (!findSelectionInJavaUnit(mUnit, status, monitor)) {
                    return status;
                }
                
            } catch (Exception e) {
                // That was not a Java file. Ignore.
            }
            
            if (mUnit == null) {
                // Check this an XML file and get the selection and its context.
                // TODO
                status.addFatalError("Selection must be inside a Java source file.");
            }
        } finally {
            monitor.done();
        }
        
        return status;
    }

    /**
     * Try to find the selected Java element in the compilation unit.
     * 
     * If selection matches a string literal, capture it, otherwise add a fatal error
     * to the status.
     * 
     * On success, advance the monitor by 3.
     */
    private boolean findSelectionInJavaUnit(ICompilationUnit unit,
            RefactoringStatus status, IProgressMonitor monitor) {
        try {
            IBuffer buffer = unit.getBuffer();

            IScanner scanner = ToolFactory.createScanner(
                    false, //tokenizeComments
                    false, //tokenizeWhiteSpace
                    false, //assertMode
                    false  //recordLineSeparator
                    );
            scanner.setSource(buffer.getCharacters());
            monitor.worked(1);

            for(int token = scanner.getNextToken();
                    token != ITerminalSymbols.TokenNameEOF;
                    token = scanner.getNextToken()) {
                if (scanner.getCurrentTokenStartPosition() <= mSelectionStart &&
                        scanner.getCurrentTokenEndPosition() >= mSelectionEnd) {
                    // found the token, but only keep of the right type
                    if (token == ITerminalSymbols.TokenNameStringLiteral) {
                        mTokenString = new String(scanner.getCurrentTokenSource());
                    }
                    break;
                } else if (scanner.getCurrentTokenStartPosition() > mSelectionEnd) {
                    // scanner is past the selection, abort.
                    break;
                }
            }
        } catch (JavaModelException e1) {
            // Error in unit.getBuffer. Ignore.
        } catch (InvalidInputException e2) {
            // Error in scanner.getNextToken. Ignore.
        } finally {
            monitor.worked(1);
        }

        if (mTokenString != null) {
            // As a literal string, the token should have surrounding quotes. Remove them.
            int len = mTokenString.length();
            if (len > 0 &&
                    mTokenString.charAt(0) == '"' &&
                    mTokenString.charAt(len - 1) == '"') {
                mTokenString = mTokenString.substring(1, len - 1);
            }
            // We need a non-empty string literal
            if (mTokenString.length() == 0) {
                mTokenString = null;
            }
        }
        
        if (mTokenString == null) {
            status.addFatalError("Please select a Java string literal.");
        }
        
        monitor.worked(1);
        return status.isOK();
    }

    /**
     * Tests from org.eclipse.jdt.internal.corext.refactoringChecks#validateEdit()
     * Might not be useful.
     * 
     * On success, advance the monitor by 2.
     * 
     * @return False if caller should abort, true if caller should continue.
     */
    private boolean checkSourceFile(IFile file,
            RefactoringStatus status,
            IProgressMonitor monitor) {
        // check whether the source file is in sync
        if (!file.isSynchronized(IResource.DEPTH_ZERO)) {
            status.addFatalError("The file is not synchronized. Please save it first.");
            return false;
        }
        monitor.worked(1);
        
        // make sure we can write to it.
        ResourceAttributes resAttr = file.getResourceAttributes();
        if (resAttr == null || resAttr.isReadOnly()) {
            status.addFatalError("The file is read-only, please make it writeable first.");
            return false;
        }
        monitor.worked(1);
        
        return true;
    }

    /**
     * Step 2 of 3 of the refactoring:
     * Check the conditions once the user filled values in the refactoring wizard,
     * then prepare the changes to be applied.
     * <p/>
     * In this case, most of the sanity checks are done by the wizard so essentially this
     * should only be called if the wizard positively validated the user input.
     * 
     * Here we do check that the target resource XML file either does not exists or
     * is not read-only.
     * 
     * @see org.eclipse.ltk.core.refactoring.Refactoring#checkFinalConditions(IProgressMonitor)
     * 
     * @throws CoreException 
     */
    @Override
    public RefactoringStatus checkFinalConditions(IProgressMonitor monitor)
            throws CoreException, OperationCanceledException {
        RefactoringStatus status = new RefactoringStatus();

        try {
            monitor.beginTask("Checking post-conditions...", 3);

            if (mXmlStringId == null || mXmlStringId.length() <= 0) {
                // this is not supposed to happen
                status.addFatalError("Missing replacement string ID");
            } else if (mTargetXmlFileWsPath == null || mTargetXmlFileWsPath.length() <= 0) {
                // this is not supposed to happen
                status.addFatalError("Missing target xml file path");
            }
            monitor.worked(1);
            
            // Either that resource must not exist or it must be a writeable file.
            IResource targetXml = getTargetXmlResource(mTargetXmlFileWsPath);
            if (targetXml != null) {
                if (targetXml.getType() != IResource.FILE) {
                    status.addFatalError(
                            String.format("XML file '%1$s' is not a file.", mTargetXmlFileWsPath));
                } else {
                    ResourceAttributes attr = targetXml.getResourceAttributes();
                    if (attr != null && attr.isReadOnly()) {
                        status.addFatalError(
                                String.format("XML file '%1$s' is read-only.",
                                        mTargetXmlFileWsPath));
                    }
                }
            }
            monitor.worked(1);
            
            if (status.hasError()) {
                return status;
            }
            
            mChanges = new ArrayList<Change>();
            
            
            // Prepare the change for the XML file.
            
            if (!isResIdDuplicate(mTargetXmlFileWsPath, mXmlStringId)) {
                // We actually change it only if the ID doesn't exist yet
                TextFileChange xmlChange = new TextFileChange(getName(), (IFile) targetXml);
                xmlChange.setTextType("xml");   //$NON-NLS-1$
                TextEdit edit = createXmlEdit((IFile) targetXml, mXmlStringId, mTokenString);
                if (edit == null) {
                    status.addFatalError(String.format("Failed to modify file %1$s",
                            mTargetXmlFileWsPath));
                }
                xmlChange.setEdit(edit);
                mChanges.add(xmlChange);
            }
            monitor.worked(1);
    
            if (status.hasError()) {
                return status;
            }
            
            // Prepare the change to the Java compilation unit
            List<Change> changes = computeJavaChanges(mUnit, mXmlStringId, mTokenString, status,
                    SubMonitor.convert(monitor, 1));
            if (changes != null) {
                mChanges.addAll(changes);
            }
            
            monitor.worked(1);
        } finally {
            monitor.done();
        }
        
        return status;
    }

    /**
     * Internal helper that actually prepares the {@link TextEdit} that adds the given
     * ID to the given XML File.
     * <p/>
     * This does not actually modify the file.
     *  
     * @param xmlFile The file resource to modify.
     * @param replacementStringId The new ID to insert.
     * @param oldString The old string, which will be the value in the XML string.
     * @return A new {@link TextEdit} that describes how to change the file.
     */
    private TextEdit createXmlEdit(IFile xmlFile, String replacementStringId, String oldString) {

        if (!xmlFile.exists()) {
            // The XML file does not exist. Simply create it.
            StringBuilder content = new StringBuilder();
            content.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"); //$NON-NLS-1$
            content.append("<resources>\n");                                //$NON-NLS-1$
            content.append("    <string name=\"").                          //$NON-NLS-1$
                        append(replacementStringId).
                        append("\">").                                      //$NON-NLS-1$
                        append(oldString).
                        append("</string>\n");                              //$NON-NLS-1$
            content.append("<resources>\n");                                //$NON-NLS-1$

            return new InsertEdit(0, content.toString());
        }

        // The file exist. Attempt to parse it as a valid XML document.
        try {
            int[] indices = new int[2];
            if (findXmlOpeningTagPos(xmlFile.getContents(), "resources", indices)) {  //$NON-NLS-1$
                // Indices[1] indicates whether we found > or />. It can only be 1 or 2.
                // Indices[0] is the position of the first character of either > or />.
                //
                // Note: we don't even try to adapt our formatting to the existing structure (we
                // could by capturing whatever whitespace is after the closing bracket and
                // applying it here before our tag, unless we were dealing with an empty
                // resource tag.)
                
                int offset = indices[0];
                int len = indices[1];
                StringBuilder content = new StringBuilder();
                content.append(">\n");                                      //$NON-NLS-1$
                content.append("    <string name=\"").                      //$NON-NLS-1$
                            append(replacementStringId).
                            append("\">").                                  //$NON-NLS-1$
                            append(oldString).
                            append("</string>");                            //$NON-NLS-1$
                if (len == 2) {
                    content.append("\n</resources>");                       //$NON-NLS-1$
                }

                return new ReplaceEdit(offset, len, content.toString());
            }
            
        } catch (CoreException e) {
            // Failed to read file. Ignore. Will return null below.
        }
        
        return null;
    }

    /**
     * Parse an XML input stream, looking for an opening tag.
     * <p/>
     * If found, returns the character offet in the buffer of the closing bracket of that
     * tag, e.g. the position of > in "<resources>". The first character is at offset 0.
     * <p/>
     * The implementation here relies on a simple character-based parser. No DOM nor SAX
     * parsing is used, due to the simplified nature of the task: we just want the first
     * opening tag, which in our case should be the document root. We deal however with
     * with the tag being commented out, so comments are skipped. We assume the XML doc
     * is sane, e.g. we don't expect the tag to appear in the middle of a string. But
     * again since in fact we want the root element, that's unlikely to happen.
     * <p/>
     * We need to deal with the case where the element is written as <resources/>, in
     * which case the caller will want to replace /> by ">...</...>". To do that we return
     * two values: the first offset of the closing tag (e.g. / or >) and the length, which
     * can only be 1 or 2. If it's 2, the caller have to deal with /> instead of just >.
     * 
     * @param contents An existing buffer to parse.
     * @param tag The tag to look for.
     * @param indices The return values: [0] is the offset of the closing bracket and [1] is
     *          the length which can be only 1 for > and 2 for />
     * @return True if we found the tag, in which case <code>indices</code> can be used.
     */
    private boolean findXmlOpeningTagPos(InputStream contents, String tag, int[] indices) {

        BufferedReader br = new BufferedReader(new InputStreamReader(contents));
        StringBuilder sb = new StringBuilder(); // scratch area

        tag = "<" + tag;
        int tagLen = tag.length();
        int maxLen = tagLen < 3 ? 3 : tagLen;
        
        try {
            int offset = 0;
            int i = 0;
            char searching = '<'; // we want opening tags
            boolean capture = false;
            boolean inComment = false;
            boolean inTag = false;
            while ((i = br.read()) != -1) {
                char c = (char) i;
                if (c == searching) {
                    capture = true;
                }
                if (capture) {
                    sb.append(c);
                    int len = sb.length();
                    if (inComment && c == '>') {
                        // is the comment being closed?
                        if (len >= 3 && sb.substring(len-3).equals("-->")) {    //$NON-NLS-1$
                            // yes, comment is closing, stop capturing
                            capture = false;
                            inComment = false;
                            sb.setLength(0);
                        }
                    } else if (inTag && c == '>') {
                        // we're capturing in our tag, waiting for the closing >, we just got it
                        // so we're totally done here. Simply detect whether it's /> or >.
                        indices[0] = offset;
                        indices[1] = 1;
                        if (sb.charAt(len - 2) == '/') {
                            indices[0]--;
                            indices[1]++;
                        }
                        return true;
                        
                    } else if (!inComment && !inTag) {
                        // not a comment and not our tag yet, so we're capturing because a
                        // tag is being opened but we don't know which one yet.
                        
                        // look for either the opening or a comment or
                        // the opening of our tag.
                        if (len == 3 && sb.equals("<--")) {                     //$NON-NLS-1$
                            inComment = true;
                        } else if (len == tagLen && sb.toString().equals(tag)) {
                            inTag = true;
                        }

                        // if we're not interested in this tag yet, deal with when to stop
                        // capturing: the opening tag ends with either any kind of whitespace
                        // or with a > or maybe there's a PI that starts with <?
                        if (!inComment && !inTag) {
                            if (c == '>' || c == '?' || c == ' ' || c == '\n' || c == '\r') {
                                // stop capturing
                                capture = false;
                                sb.setLength(0);
                            }
                        }
                    }

                    if (capture && len > maxLen) {
                        // in any case we don't need to capture more than the size of our tag
                        // or the comment opening tag
                        sb.deleteCharAt(0);
                    }
                }
                offset++;
            }
        } catch (IOException e) {
            // Ignore.
        } finally {
            try {
                br.close();
            } catch (IOException e) {
                // oh come on...
            }
        }
        
        return false;
    }

    private List<Change> computeJavaChanges(ICompilationUnit unit,
            String xmlStringId,
            String tokenString,
            RefactoringStatus status,
            SubMonitor subMonitor) {

        // Get the Android package name from the Android Manifest. We need it to create
        // the FQCN of the R class.
        String packageName = null;
        String error = null;
        IProject proj = unit.getJavaProject().getProject();
        IResource manifestFile = proj.findMember(AndroidConstants.FN_ANDROID_MANIFEST);
        if (manifestFile == null || manifestFile.getType() != IResource.FILE) {
            error = "File not found";
        } else {
            try {
                AndroidManifestParser manifest = AndroidManifestParser.parseForData(
                        (IFile) manifestFile);
                if (manifest == null) {
                    error = "Invalid content";
                } else {
                    packageName = manifest.getPackage();
                    if (packageName == null) {
                        error = "Missing package definition";
                    }
                }
            } catch (CoreException e) {
                error = e.getLocalizedMessage();
            }
        }
        
        if (error != null) {
            status.addFatalError(
                    String.format("Failed to parse file %1$s: %2$s.",
                            manifestFile.getFullPath(), error));
            return null;
        }
        
        // TODO in a future version we might want to collect various Java files that
        // need to be updated in the same project and process them all together.
        // To do that we need to use an ASTRequestor and parser.createASTs, kind of
        // like this:
        //
        // ASTRequestor requestor = new ASTRequestor() {
        //    @Override
        //    public void acceptAST(ICompilationUnit sourceUnit, CompilationUnit astNode) {
        //        super.acceptAST(sourceUnit, astNode);
        //        // TODO process astNode
        //    }  
        // };
        // ...
        // parser.createASTs(compilationUnits, bindingKeys, requestor, monitor)
        // 
        // and then add multiple TextFileChange to the changes arraylist.

        // Right now the changes array will contain one TextFileChange at most.
        ArrayList<Change> changes = new ArrayList<Change>();

        // This is the unit that will be modified.
        TextFileChange change = new TextFileChange(getName(), (IFile) unit.getResource());
        change.setTextType("java"); //$NON-NLS-1$

        // Create an AST for this compilation unit
        ASTParser parser = ASTParser.newParser(AST.JLS3);
        parser.setProject(unit.getJavaProject());
        parser.setSource(unit);
        parser.setResolveBindings(true);
        ASTNode node = parser.createAST(subMonitor.newChild(1));

        // The ASTNode must be a CompilationUnit, by design
        if (!(node instanceof CompilationUnit)) {
            status.addFatalError(String.format("Internal error: ASTNode class %s",  //$NON-NLS-1$
                    node.getClass()));
            return null;
        }

        // ImportRewrite will allow us to add the new type to the imports and will resolve
        // what the Java source must reference, e.g. the FQCN or just the simple name.
        ImportRewrite ir = ImportRewrite.create((CompilationUnit) node, true);
        String Rqualifier = packageName + ".R"; //$NON-NLS-1$
        Rqualifier = ir.addImport(Rqualifier);

        // Rewrite the AST itself via an ASTVisitor
        AST ast = node.getAST();
        ASTRewrite ar = ASTRewrite.create(ast);
        ReplaceStringsVisitor visitor = new ReplaceStringsVisitor(ast, ar,
                tokenString, Rqualifier, xmlStringId);
        node.accept(visitor);
        
        // Finally prepare the change set
        try {
            MultiTextEdit edit = new MultiTextEdit();

            // Create the edit to change the imports, only if anything changed
            TextEdit subEdit = ir.rewriteImports(subMonitor.newChild(1));
            if (subEdit.hasChildren()) {
                edit.addChild(subEdit);
            }

            // Create the edit to change the Java source, only if anything changed
            subEdit = ar.rewriteAST();
            if (subEdit.hasChildren()) {
                edit.addChild(subEdit);
            }

            // Only create a change set if any edit was collected
            if (edit.hasChildren()) {
                change.setEdit(edit);
                changes.add(change);
            }
            
            // TODO to modify another Java source, loop back to the creation of the
            // TextFileChange and accumulate in changes. Right now only one source is
            // modified.
            
            if (changes.size() > 0) {
                return changes;
            }
            
        } catch (CoreException e) {
            // ImportRewrite.rewriteImports failed.
            status.addFatalError(e.getMessage());
        }
        return null;
    }

    public class ReplaceStringsVisitor extends ASTVisitor {

        private final AST mAst;
        private final ASTRewrite mRewriter;
        private final String mOldString;
        private final String mRQualifier;
        private final String mXmlId;

        public ReplaceStringsVisitor(AST ast,
                ASTRewrite astRewrite,
                String oldString,
                String rQualifier,
                String xmlId) {
            mAst = ast;
            mRewriter = astRewrite;
            mOldString = oldString;
            mRQualifier = rQualifier;
            mXmlId = xmlId;
        }

        @Override
        public boolean visit(StringLiteral node) {
            if (node.getLiteralValue().equals(mOldString)) {
                
                Name qualifierName = mAst.newName(mRQualifier + ".string"); //$NON-NLS-1$
                SimpleName idName = mAst.newSimpleName(mXmlId);
                QualifiedName newNode = mAst.newQualifiedName(qualifierName, idName);
                
                TextEditGroup editGroup = new TextEditGroup(getName());
                mRewriter.replace(node, newNode, editGroup);
            }
            return super.visit(node);
        }
    }

    /**
     * Step 3 of 3 of the refactoring: returns the {@link Change} that will be able to do the
     * work and creates a descriptor that can be used to replay that refactoring later. 
     * 
     * @see org.eclipse.ltk.core.refactoring.Refactoring#createChange(org.eclipse.core.runtime.IProgressMonitor)
     * 
     * @throws CoreException 
     */
    @Override
    public Change createChange(IProgressMonitor monitor)
            throws CoreException, OperationCanceledException {

        try {
            monitor.beginTask("Applying changes...", 1);
            
            CompositeChange change = new CompositeChange(
                    getName(),
                    mChanges.toArray(new Change[mChanges.size()])) {
                @Override
                public ChangeDescriptor getDescriptor() {

                    String comment = String.format(
                            "Extracts string '%1$s' into R.string.%2$s",
                            mTokenString,
                            mXmlStringId);
                    
                    ExtractStringDescriptor desc = new ExtractStringDescriptor(
                            mUnit.getJavaProject().getElementName(), //project
                            comment, //description
                            comment, //comment
                            createArgumentMap());
                    
                    return new RefactoringChangeDescriptor(desc);
                }
            };
            
            monitor.worked(1);
            
            return change;
            
        } finally {
            monitor.done();
        }
        
    }

    /**
     * Utility method used by the wizard to check whether the given string ID is already
     * defined in the XML file which path is given.
     * 
     * @param xmlFileWsPath The project path of the XML file, e.g. "/res/values/strings.xml".
     *          The given file may or may not exist.
     * @param stringId The string ID to find.
     * @return True if such a string ID is already defined.
     */
    public boolean isResIdDuplicate(String xmlFileWsPath, String stringId) {
        // This is going to be called many times on the same file.
        // Build a cache of the existing IDs for a given file.
        if (mResIdCache == null) {
            mResIdCache = new HashMap<String, HashSet<String>>();
        }
        HashSet<String> cache = mResIdCache.get(xmlFileWsPath);
        if (cache == null) {
            cache = getResIdsForFile(xmlFileWsPath);
            mResIdCache.put(xmlFileWsPath, cache);
        }
        
        return cache.contains(stringId);
    }

    /**
     * Extract all the defined string IDs from a given file using XPath.
     * 
     * @param xmlFileWsPath The project path of the file to parse. It may not exist.
     * @return The set of all string IDs defined in the file. The returned set is always non
     *   null. It is empty if the file does not exist.
     */
    private HashSet<String> getResIdsForFile(String xmlFileWsPath) {
        HashSet<String> ids = new HashSet<String>();
        
        if (mXPath == null) {
            mXPath = AndroidXPathFactory.newXPath();
        }

        // Access the project that contains the resource that contains the compilation unit
        IResource resource = getTargetXmlResource(xmlFileWsPath);
        
        if (resource != null && resource.exists() && resource.getType() == IResource.FILE) {
            InputSource source;
            try {
                source = new InputSource(((IFile) resource).getContents());

                // We want all the IDs in an XML structure like this:
                // <resources>
                //    <string name="ID">something</string>
                // </resources>
                
                String xpathExpr = "/resources/string/@name";   //$NON-NLS-1$
                
                Object result = mXPath.evaluate(xpathExpr, source, XPathConstants.NODESET);
                if (result instanceof NodeList) {
                    NodeList list = (NodeList) result;
                    for (int n = list.getLength() - 1; n >= 0; n--) {
                        String id = list.item(n).getNodeValue();
                        ids.add(id);
                    }
                }
                
            } catch (CoreException e1) {
                // IFile.getContents failed. Ignore.
            } catch (XPathExpressionException e) {
                // mXPath.evaluate failed. Ignore.
            }
        }
        
        return ids;
    }

    /**
     * Given a file project path, returns its resource in the same project than the
     * compilation unit. The resource may not exist.
     */
    private IResource getTargetXmlResource(String xmlFileWsPath) {
        IProject proj = mFile.getProject();
        IResource resource = proj.getFile(xmlFileWsPath);
        return resource;
    }

    /**
     * Sets the replacement string ID. Used by the wizard to set the user input.
     */
    public void setReplacementStringId(String replacementStringId) {
        mXmlStringId = replacementStringId;
    }

    /**
     * Sets the target file. This is a project path, e.g. "/res/values/strings.xml".
     * Used by the wizard to set the user input.
     */
    public void setTargetFile(String targetXmlFileWsPath) {
        mTargetXmlFileWsPath = targetXmlFileWsPath;
    }

}
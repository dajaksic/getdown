//
// $Id: Application.java,v 1.8 2004/07/07 16:18:23 mdb Exp $

package com.threerings.getdown.data;

import java.awt.Rectangle;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import java.net.MalformedURLException;
import java.net.URL;
import java.security.MessageDigest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;

import com.samskivert.io.NestableIOException;
import com.samskivert.io.StreamUtil;
import com.samskivert.text.MessageUtil;
import com.samskivert.util.RunAnywhere;
import com.samskivert.util.StringUtil;

import org.apache.commons.io.StreamUtils;

import com.threerings.getdown.Log;
import com.threerings.getdown.util.ConfigUtil;

/**
 * Parses and provide access to the information contained in the
 * <code>getdown.txt</code> configuration file.
 */
public class Application
{
    /** The name of our configuration file. */
    public static final String CONFIG_FILE = "getdown.txt";

    /** The name of our target version file. */
    public static final String VERSION_FILE = "version.txt";

    /** Used to communicate information about the UI displayed when
     * updating the application. */
    public static class UpdateInterface
    {
        /** The human readable name of this application. */
        public String name;

        /** The dimensions of the progress bar. */
        public Rectangle progress;

        /** The dimensions of the status display. */
        public Rectangle status;

        /** The path (relative to the appdir) to the background image. */
        public String background;
    }

    /**
     * Creates an application instance which records the location of the
     * <code>getdown.txt</code> configuration file from the supplied
     * application directory.
     */
    public Application (File appdir)
    {
        _appdir = appdir;
        _config = getLocalPath(CONFIG_FILE);
    }

    /**
     * Returns a resource that refers to the application configuration
     * file itself.
     */
    public Resource getConfigResource ()
    {
        try {
            return createResource(CONFIG_FILE);
        } catch (Exception e) {
            throw new RuntimeException("Booched appbase '" + _appbase + "'!?");
        }
    }

    /**
     * Returns a list of the code {@link Resource} objects used by this
     * application.
     */
    public List getCodeResources ()
    {
        return _codes;
    }

    /**
     * Returns a list of the non-code {@link Resource} objects used by
     * this application.
     */
    public List getResources ()
    {
        return _resources;
    }

    /**
     * Instructs the application to parse its <code>getdown.txt</code>
     * configuration and prepare itself for operation. The application
     * base URL will be parsed first so that if there are errors
     * discovered later, the caller can use the application base to
     * download a new <code>config.txt</code> file and try again.
     *
     * @return a configured UpdateInterface instance that will be used to
     * configure the update UI.
     *
     * @exception IOException thrown if there is an error reading the file
     * or an error encountered during its parsing.
     */
    public UpdateInterface init ()
        throws IOException
    {
        // parse our configuration file
        HashMap cdata = null;
        try {
            cdata = ConfigUtil.parseConfig(_config);
        } catch (FileNotFoundException fnfe) {
            // thanks to funny windows bullshit, we have to do this backup
            // file fiddling in case we got screwed while updating our
            // very critical getdown config file
            File cbackup = getLocalPath(CONFIG_FILE + "_old");
            if (cbackup.exists()) {
                cdata = ConfigUtil.parseConfig(cbackup);
            } else {
                throw fnfe;
            }
        }

        // first determine our application base, this way if anything goes
        // wrong later in the process, our caller can use the appbase to
        // download a new configuration file
        String appbase = (String)cdata.get("appbase");
        if (appbase == null) {
            throw new IOException("m.missing_appbase");
        }
        try {
            // make sure there's a trailing slash
            if (!appbase.endsWith("/")) {
                appbase = appbase + "/";
            }
            _appbase = new URL(appbase);
        } catch (Exception e) {
            String err = MessageUtil.tcompose("m.invalid_appbase", appbase);
            throw new NestableIOException(err, e);
        }

        // extract our version information
        String vstr = (String)cdata.get("version");
        if (vstr != null) {
            try {
                _version = Integer.parseInt(vstr);
            } catch (Exception e) {
                String err = MessageUtil.tcompose("m.invalid_version", vstr);
                throw new NestableIOException(err, e);
            }
        }

        // if we are a versioned deployment, create a versioned appbase
        if (_version < 0) {
            _vappbase = _appbase;
        } else {
            try {
                _vappbase = new URL(
                    StringUtil.replace(_appbase.toString(), "%VERSION%", vstr));
            } catch (MalformedURLException mue) {
                String err = MessageUtil.tcompose("m.invalid_appbase", appbase);
                throw new NestableIOException(err, mue);
            }
        }

        // determine our application class name
        _class = (String)cdata.get("class");
        if (_class == null) {
            throw new IOException("m.missing_class");
        }

        // clear our arrays as we may be reinitializing
        _codes.clear();
        _resources.clear();
        _jvmargs.clear();
        _appargs.clear();

        // parse our code resources
        String[] codes = ConfigUtil.getMultiValue(cdata, "code");
        if (codes == null) {
            throw new IOException("m.missing_code");
        }
        for (int ii = 0; ii < codes.length; ii++) {
            try {
                _codes.add(createResource(codes[ii]));
            } catch (Exception e) {
                Log.warning("Invalid code resource '" + codes[ii] + "'." + e);
            }
        }

        // parse our non-code resources
        String[] rsrcs = ConfigUtil.getMultiValue(cdata, "resource");
        if (rsrcs != null) {
            for (int ii = 0; ii < rsrcs.length; ii++) {
                try {
                    _resources.add(createResource(rsrcs[ii]));
                } catch (Exception e) {
                    Log.warning("Invalid resource '" + rsrcs[ii] + "'. " + e);
                }
            }
        }

        // transfer our JVM arguments
        String[] jvmargs = ConfigUtil.getMultiValue(cdata, "jvmarg");
        if (jvmargs != null) {
            for (int ii = 0; ii < jvmargs.length; ii++) {
                _jvmargs.add(jvmargs[ii]);
            }
        }

        // transfer our application arguments
        String[] appargs = ConfigUtil.getMultiValue(cdata, "apparg");
        if (appargs != null) {
            for (int ii = 0; ii < appargs.length; ii++) {
                _appargs.add(appargs[ii]);
            }
        }

        // parse and return our application config
        UpdateInterface ui = new UpdateInterface();
        ui.name = (String)cdata.get("ui.name");
        ui.progress = parseRect(
            "ui.progress", (String)cdata.get("ui.progress"));
        ui.status = parseRect("ui.progress", (String)cdata.get("ui.status"));
        ui.background = (String)cdata.get("ui.background");
        return ui;
    }

    /**
     * Returns a URL from which the specified path can be fetched. Our
     * application base URL is properly versioned and combined with the
     * supplied path.
     */
    public URL getRemoteURL (String path)
        throws MalformedURLException
    {
        return new URL(_vappbase, path);
    }

    /**
     * Returns the local path to the specified resource.
     */
    public File getLocalPath (String path)
    {
        return new File(_appdir, path);
    }

    /**
     * Attempts to redownload the <code>getdown.txt</code> file based on
     * information parsed from a previous call to {@link #init}.
     */
    public void attemptRecovery ()
        throws IOException
    {
        downloadControlFile(CONFIG_FILE);
    }

    /**
     * Invokes the process associated with this application definition.
     */
    public Process createProcess ()
        throws IOException
    {
        // create our classpath
        StringBuffer cpbuf = new StringBuffer();
        for (Iterator iter = _codes.iterator(); iter.hasNext(); ) {
            if (cpbuf.length() > 0) {
                cpbuf.append(File.pathSeparator);
            }
            Resource rsrc = (Resource)iter.next();
            cpbuf.append(rsrc.getLocal().getAbsolutePath());
        }

        // we'll need the JVM, classpath, JVM args, class name and app args
        String[] args = new String[4 + _jvmargs.size() + _appargs.size()];
        int idx = 0;

        // reconstruct the path to the JVM
        args[idx++] = System.getProperty("java.home") +
            File.separator + "bin" + File.separator + "java";

        // add the classpath arguments
        args[idx++] = "-classpath";
        args[idx++] = cpbuf.toString();

        // add the JVM arguments
        for (Iterator iter = _jvmargs.iterator(); iter.hasNext(); ) {
            args[idx++] = processArg((String)iter.next());
        }

        // add the application class name
        args[idx++] = _class;

        // finally add the application arguments
        for (Iterator iter = _appargs.iterator(); iter.hasNext(); ) {
            args[idx++] = processArg((String)iter.next());
        }

        Log.info("Running " + StringUtil.join(args, "\n  "));
        return Runtime.getRuntime().exec(args, null);
    }

    /** Replaces the application directory and version in any argument. */
    protected String processArg (String arg)
    {
        if (arg.indexOf("%APPDIR%") != -1) {
            arg = StringUtil.replace(
                arg, "%APPDIR%", _appdir.getAbsolutePath());
        }
        if (arg.indexOf("%VERSION%") != -1) {
            arg = StringUtil.replace(arg, "%VERSION%", "" + _version);
        }
        return arg;
    }

    /**
     * Loads the <code>digest.txt</code> file and verifies the contents of
     * both that file and the <code>getdown.text</code> file. Then it
     * loads the <code>version.txt</code> and decides whether or not the
     * application needs to be updated or whether we can proceed to
     * verification and execution.
     *
     * @return true if the application needs to be updated, false if it is
     * up to date and can be verified and executed.
     *
     * @exception IOException thrown if we encounter an unrecoverable
     * error while verifying the metadata.
     */
    public boolean verifyMetadata ()
        throws IOException
    {
        Log.info("Verifying application: " + _appbase);
        Log.info("Version: " + _version);
        Log.info("Class: " + _class);
//         Log.info("Code: " + StringUtil.toString(_codes.iterator()));
//         Log.info("Resources: " + StringUtil.toString(_resources.iterator()));
//         Log.info("JVM Args: " + StringUtil.toString(_jvmargs.iterator()));
//         Log.info("App Args: " + StringUtil.toString(_appargs.iterator()));

        // create our digester which will read in the contents of the
        // digest file and validate itself
        try {
            _digest = new Digest(_appdir);
        } catch (IOException ioe) {
            Log.info("Failed to load digest: " + ioe.getMessage() + ". " +
                     "Attempting recovery...");
        }

        // if we have no version, then we are running in unversioned mode
        // so we need to download our digest.txt file on every invocation
        if (_version == -1) {
            // make a note of the old meta-digest, if this changes we need
            // to revalidate all of our resources as one or more of them
            // have also changed
            String olddig = (_digest == null) ? "" : _digest.getMetaDigest();
            downloadControlFile(Digest.DIGEST_FILE);
            _digest = new Digest(_appdir);
            if (!olddig.equals(_digest.getMetaDigest())) {
                Log.info("Unversioned digest changed. Revalidating...");
                clearValidationMarkers();
            }

        } else if (_digest == null) {
            // if we failed to load the digest, try to redownload the
            // digest file and give it another good college try; this time
            // we allow exceptions to propagate up to the caller as there
            // is nothing else we can do to recover
            downloadControlFile(Digest.DIGEST_FILE);
            _digest = new Digest(_appdir);
        }

        // now verify the contents of our main config file
        Resource crsrc = getConfigResource();
        if (!_digest.validateResource(crsrc)) {
            // attempt to redownload the file; again we pass errors up to
            // our caller because we have no recourse to recovery
            downloadControlFile(CONFIG_FILE);
            // if the new copy validates, reinitialize ourselves;
            // otherwise report baffling hoseage
            if (_digest.validateResource(crsrc)) {
                init();
            }
        }

        // start by assuming we are happy with our version
        _targetVersion = _version;

        // now read in the contents of the version.txt file (if any)
        File vfile = getLocalPath(VERSION_FILE);
        FileInputStream fin = null;
        try {
            fin = new FileInputStream(vfile);
            BufferedReader bin = new BufferedReader(
                new InputStreamReader(fin));
            String vstr = bin.readLine();
            if (!StringUtil.blank(vstr)) {
                _targetVersion = Integer.parseInt(vstr);
            }
        } catch (Exception e) {
            Log.info("Unable to read version file: " + e.getMessage());
        } finally {
            StreamUtil.close(fin);
        }

        // next parse any custom user interface information

        // finally let the caller know if we need an update
        return _version != _targetVersion;
    }

    /**
     * Verifies the code and media resources associated with this
     * application. A list of resources that do not exist or fail the
     * verification process will be returned. If all resources are ready
     * to go, null will be returned and the application is considered
     * ready to run.
     */
    public List verifyResources ()
    {
        ArrayList failures = new ArrayList();
        verifyResources(_codes.iterator(), failures);
        verifyResources(_resources.iterator(), failures);
        return (failures.size() == 0) ? null : failures;
    }

    /** A helper function used by {@link #verifyResources()}. */
    protected void verifyResources (Iterator rsrcs, List failures)
    {
        while (rsrcs.hasNext()) {
            Resource rsrc = (Resource)rsrcs.next();
            if (rsrc.isMarkedValid()) {
                continue;
            }

            try {
                if (_digest.validateResource(rsrc)) {
                    // make a note that this file is kosher
                    rsrc.markAsValid();
                    continue;
                }

            } catch (Exception e) {
                Log.info("Failure validating resource [rsrc=" + rsrc +
                         ", error=" + e + "]. Requesting redownload...");
            }
            failures.add(rsrc);
        }
    }

    /** Clears all validation marker files. */
    protected void clearValidationMarkers ()
    {
        clearValidationMarkers(_codes.iterator());
        clearValidationMarkers(_resources.iterator());
    }

    /** Clears all validation marker files for the resources in the
     * supplied iterator. */
    protected void clearValidationMarkers (Iterator iter)
    {
        while (iter.hasNext()) {
            ((Resource)iter.next()).clearMarker();
        }
    }

    /**
     * Downloads a new copy of the specified control file and, if the
     * download is successful, moves it over the old file on the
     * filesystem.
     */
    protected void downloadControlFile (String path)
        throws IOException
    {
        File target = getLocalPath(path + "_new");
        URL targetURL = null;
        try {
            targetURL = getRemoteURL(path);
        } catch (Exception e) {
            Log.warning("Requested to download invalid control file " +
                        "[appbase=" + _appbase + ", path=" + path +
                        ", error=" + e + "].");
            throw new NestableIOException("Invalid path '" + path + "'.", e);
        }

        Log.info("Attempting to refetch '" + path + "' from '" +
                 targetURL + "'.");

        // stream the URL into our temporary file
        InputStream fin = null;
        FileOutputStream fout = null;
        try {
            fin = targetURL.openStream();
            fout = new FileOutputStream(target);
            StreamUtils.pipe(fin, fout);
        } finally {
            StreamUtil.close(fin);
            StreamUtil.close(fout);
        }

        // Windows is a wonderful operating system, it won't let you
        // rename a file overtop of another one; thus to avoid running the
        // risk of getting royally fucked, we have to do this complicated
        // backup bullshit; this way if the shit hits the fan before we
        // get the new copy into place, we should be able to read from the
        // backup copy; yay!
        File original = getLocalPath(path);
        if (RunAnywhere.isWindows() && original.exists()) {
            File backup = getLocalPath(path + "_old");
            if (backup.exists() && !backup.delete()) {
                Log.warning("Failed to delete " + backup + ".");
            }
            if (!original.renameTo(backup)) {
                Log.warning("Failed to move " + original + " to backup. " +
                            "We will likely fail to replace it with " +
                            target + ".");
            }
        }

        // now attempt to replace the current file with the new one
        if (!target.renameTo(original)) {
            throw new IOException(
                "Failed to rename(" + target + ", " + original + ")");
        }
    }

    /** Helper function for creating {@link Resource} instances. */
    protected Resource createResource (String path)
        throws MalformedURLException
    {
        return new Resource(path, getRemoteURL(path), getLocalPath(path));
    }

    /** Used to parse rectangle specifications from the config file. */
    protected Rectangle parseRect (String name, String value)
    {
        if (!StringUtil.blank(value)) {
            int[] v = StringUtil.parseIntArray(value);
            if (v != null && v.length == 4) {
                return new Rectangle(v[0], v[1], v[2], v[3]);
            } else {
                Log.warning("Ignoring invalid '" + name + "' config '" +
                            value + "'.");
            }
        }
        return null;
    }

    protected File _appdir;
    protected File _config;
    protected Digest _digest;

    protected int _version = -1;
    protected int _targetVersion = -1;
    protected URL _appbase, _vappbase;
    protected String _class;

    protected ArrayList _codes = new ArrayList();
    protected ArrayList _resources = new ArrayList();

    protected ArrayList _jvmargs = new ArrayList();
    protected ArrayList _appargs = new ArrayList();
}
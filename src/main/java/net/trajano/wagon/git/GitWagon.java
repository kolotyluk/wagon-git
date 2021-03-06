package net.trajano.wagon.git;

import static java.lang.String.format;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.LinkedList;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.maven.wagon.ConnectionException;
import org.apache.maven.wagon.InputData;
import org.apache.maven.wagon.OutputData;
import org.apache.maven.wagon.ResourceDoesNotExistException;
import org.apache.maven.wagon.StreamWagon;
import org.apache.maven.wagon.TransferFailedException;
import org.apache.maven.wagon.Wagon;
import org.apache.maven.wagon.authentication.AuthenticationException;
import org.apache.maven.wagon.authorization.AuthorizationException;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.util.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

/**
 * Git Wagon. Due to issues with the way maven-site-plugin is improperly sending
 * requests that assume the target repository is a file system, the handling of
 * git URIs fails. This performs an inefficient, but working method of creating
 * a clone per request, but only once per Git repository.
 */
@Component(role = Wagon.class, hint = "git", instantiationStrategy = "per-lookup")
public class GitWagon extends StreamWagon {

    /**
     * Logger.
     */
    private static final Logger LOG;

    /**
     * Messages resource path.
     */
    private static final String MESSAGES = "META-INF/Messages";

    /**
     * Resource bundle.
     */
    private static final ResourceBundle R;

    static {
        LOG = Logger.getLogger("net.trajano.wagon.git", MESSAGES);
        R = ResourceBundle.getBundle(MESSAGES);
    }

    /**
     * Credentials provider.
     */
    private UsernamePasswordCredentialsProvider credentialsProvider;

    /**
     * Git cache.
     */
    private final ConcurrentMap<String, Git> gitCache = new ConcurrentHashMap<String, Git>();

    /**
     * Git URI.
     */
    private GitUri gitUri;

    /**
     * This will commit the local changes and push them to the repository. If
     * the method is unable to push to the repository without force, it will
     * throw an exception. {@inheritDoc}
     */
    @Override
    public void closeConnection() throws ConnectionException {
        try {
            for (final String gitRemoteUri : gitCache.keySet()) {
                final Git git = gitCache.get(gitRemoteUri);
                git.add().addFilepattern(".").call(); //$NON-NLS-1$
                git.commit().setMessage(R.getString("commitmessage")).call(); //$NON-NLS-1$
                git.push().setRemote(gitRemoteUri)
                        .setCredentialsProvider(credentialsProvider).call();
                git.close();
                FileUtils.deleteDirectory(git.getRepository().getDirectory());
            }
        } catch (final GitAPIException e) {
            throw new ConnectionException(e.getMessage(), e);
        } catch (final IOException e) {
            throw new ConnectionException(e.getMessage(), e);
        }
    }

    /**
     * This will read from the working copy. File modification date would not be
     * available as it does not really have any meaningful value. {@inheritDoc}
     * 
     * @throws ResourceDoesNotExistException
     *             when the file does not exist
     * @throws AuthorizationException
     *             when the file cannot be read
     */
    @Override
    public void fillInputData(final InputData inputData)
            throws TransferFailedException, ResourceDoesNotExistException,
            AuthorizationException {
        try {
            final File file = getFileForResource(inputData.getResource()
                    .getName());
            if (!file.exists()) {
                throw new ResourceDoesNotExistException(format(
                        R.getString("filenotfound"), file)); //$NON-NLS-1$
            }
            if (!file.canRead()) {
                throw new AuthorizationException(format(
                        R.getString("cannotreadfile"), file)); //$NON-NLS-1$
            }
            inputData.setInputStream(new FileInputStream(file));
            inputData.getResource().setContentLength(file.length());
        } catch (final IOException e) {
            throw new TransferFailedException(e.getMessage(), e);
        } catch (final GitAPIException e) {
            throw new TransferFailedException(e.getMessage(), e);
        } catch (final URISyntaxException e) {
            throw new TransferFailedException(e.getMessage(), e);
        }
    }

    /**
     * This will write to the working copy. {@inheritDoc}
     */
    @Override
    public void fillOutputData(final OutputData outputData)
            throws TransferFailedException {
        try {
            final File file = getFileForResource(outputData.getResource()
                    .getName());
            if (!file.getParentFile().mkdirs()
                    && !file.getParentFile().exists()) {
                throw new TransferFailedException(format(
                        R.getString("unabletocreatedirs"), //$NON-NLS-1$
                        file.getParentFile()));
            }
            outputData.setOutputStream(new FileOutputStream(file));
        } catch (final IOException e) {
            throw new TransferFailedException(e.getMessage(), e);
        } catch (final GitAPIException e) {
            throw new TransferFailedException(e.getMessage(), e);
        } catch (final URISyntaxException e) {
            throw new TransferFailedException(e.getMessage(), e);
        }
    }

    /**
     * This will get the file object for the given resource relative to the
     * {@link Git} specified for the connection. It will handle resources where
     * it jumps up past the parent folder.
     * 
     * @param resourceName
     *            resource name.
     * @return file used for the resourse.
     * @throws IOException
     * @throws GitAPIException
     * @throws URISyntaxException
     */
    private File getFileForResource(final String resourceName)
            throws GitAPIException, IOException, URISyntaxException {
        // /foo/bar/foo.git + ../bar.git == /foo/bar/bar.git + /
        // /foo/bar/foo.git + ../bar.git/abc == /foo/bar/bar.git + /abc
        final GitUri resolved = gitUri.resolve(resourceName);
        final Git resourceGit = getGit(resolved.getGitRepositoryUri());
        return new File(resourceGit.getRepository().getWorkTree(),
                resolved.getResource());
    }

    /**
     * {@inheritDoc}
     * <p>
     * Warnings are suppressed for false positive with Sonar and multiple
     * exceptions on public API. {@inheritDoc}
     * </p>
     */
    @Override
    @SuppressWarnings("all")
    public List<String> getFileList(final String directory)
            throws TransferFailedException, ResourceDoesNotExistException,
            AuthorizationException {
        final File dir;
        try {
            dir = getFileForResource(directory);
        } catch (final GitAPIException e) {
            throw new AuthorizationException(e.getMessage(), e);
        } catch (final IOException e) {
            throw new TransferFailedException(e.getMessage(), e);
        } catch (final URISyntaxException e) {
            throw new ResourceDoesNotExistException(e.getMessage(), e);
        }
        final File[] files = dir.listFiles();
        if (files == null) {
            throw new ResourceDoesNotExistException(format(
                    R.getString("dirnotfound"), dir)); //$NON-NLS-1$
        }
        final List<String> list = new LinkedList<String>();
        for (final File file : files) {
            String name = file.getName();
            if (file.isDirectory() && !name.endsWith("/")) { //$NON-NLS-1$
                name += "/"; // NOPMD this is easier to read. //$NON-NLS-1$
            }
            list.add(name);
        }
        return list;
    }

    /**
     * This will create or refresh the working copy. If the working copy cannot
     * be pulled cleanly this method will fail.
     * 
     * @param gitRepositoryUri
     *            remote git repository URI string
     * @return git
     * @throws GitAPIException
     * @throws IOException
     * @throws URISyntaxException
     */
    private Git getGit(final String gitRepositoryUri) throws GitAPIException,
            IOException, URISyntaxException {
        final Git cachedGit = gitCache.get(gitRepositoryUri);
        if (cachedGit != null) {
            return cachedGit;
        }
        final File gitDir = File.createTempFile(
                gitRepositoryUri.replaceAll("[^A-Za-z]", "_"), "wagon-git"); //$NON-NLS-1$
        gitDir.delete();
        gitDir.mkdir();

        credentialsProvider = new UsernamePasswordCredentialsProvider(
                getAuthenticationInfo().getUserName(), getAuthenticationInfo()
                        .getPassword() == null ? "" //$NON-NLS-1$
                        : getAuthenticationInfo().getPassword());
        final Git git = Git.cloneRepository().setURI(gitRepositoryUri)
                .setCredentialsProvider(credentialsProvider)
                .setBranch(gitUri.getBranchName()).setDirectory(gitDir).call();
        if (!gitUri.getBranchName().equals(git.getRepository().getBranch())) {
            LOG.log(Level.INFO, "missingbranch", gitUri.getBranchName());
            final RefUpdate refUpdate = git.getRepository().getRefDatabase()
                    .newUpdate(Constants.HEAD, true);
            refUpdate.setForceUpdate(true);
            refUpdate.link("refs/heads/" + gitUri.getBranchName()); //$NON-NLS-1$
        }
        gitCache.put(gitRepositoryUri, git);
        return git;
    }

    /**
     * Sets the initial git URI.
     */
    @Override
    protected void openConnectionInternal() throws ConnectionException,
            AuthenticationException {
        try {
            gitUri = new GitUri(getRepository().getUrl());
        } catch (final URISyntaxException e) {
            throw new ConnectionException(e.getMessage(), e);
        }
    }

    /**
     * If the destination directory is not inside the source directory (denoted
     * by starting with "../"), then another git repository is registered.
     * Warnings are suppressed for false positive with Sonar and multiple
     * exceptions on public API. {@inheritDoc}
     */
    @Override
    @SuppressWarnings("all")
    public void putDirectory(final File sourceDirectory,
            final String destinationDirectory) throws TransferFailedException,
            ResourceDoesNotExistException, AuthorizationException {
        try {
            if (!sourceDirectory.isDirectory()) {
                throw new ResourceDoesNotExistException(format(
                        R.getString("dirnotfound"), sourceDirectory)); //$NON-NLS-1$
            }
            final File fileForResource = getFileForResource(destinationDirectory);
            FileUtils.copyDirectoryStructure(sourceDirectory, fileForResource);
        } catch (final IOException e) {
            throw new TransferFailedException(e.getMessage(), e);
        } catch (final GitAPIException e) {
            throw new TransferFailedException(e.getMessage(), e);
        } catch (final URISyntaxException e) {
            throw new TransferFailedException(e.getMessage(), e);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean resourceExists(final String resourceName)
            throws TransferFailedException {
        final File file;
        try {
            file = getFileForResource(resourceName);
        } catch (final GitAPIException e) {
            throw new TransferFailedException(e.getMessage(), e);
        } catch (final IOException e) {
            throw new TransferFailedException(e.getMessage(), e);
        } catch (final URISyntaxException e) {
            throw new TransferFailedException(e.getMessage(), e);
        }

        if (resourceName.endsWith("/")) { //$NON-NLS-1$
            return file.isDirectory();
        }

        return file.exists();
    }

    /**
     * Directory copy is supported.
     * 
     * @return true
     */
    @Override
    public boolean supportsDirectoryCopy() {
        return true;
    }
}

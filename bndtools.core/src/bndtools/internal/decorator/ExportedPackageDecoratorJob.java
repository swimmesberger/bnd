package bndtools.internal.decorator;

import java.text.MessageFormat;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.SortedSet;
import java.util.TreeSet;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.PlatformUI;
import org.osgi.framework.Version;

import aQute.bnd.build.Project;
import aQute.bnd.build.Workspace;
import aQute.lib.osgi.Builder;
import aQute.lib.osgi.Constants;
import aQute.lib.osgi.Jar;
import aQute.lib.osgi.Processor;
import aQute.libg.header.Attrs;
import aQute.libg.header.Parameters;
import bndtools.Central;
import bndtools.Plugin;
import bndtools.api.ILogger;
import bndtools.utils.SWTConcurrencyUtil;

public class ExportedPackageDecoratorJob extends Job {

    private final IProject project;
    private final ILogger logger;

    public ExportedPackageDecoratorJob(IProject project, ILogger logger) {
        super("Update exported packages");
        this.project = project;
        this.logger = logger;
    }

    @Override
    protected IStatus run(IProgressMonitor monitor) {
        try {
            Project model = Workspace.getProject(project.getLocation().toFile());
            Collection< ? extends Builder> builders = model.getSubBuilders();

            Map<String,SortedSet<Version>> allExports = new HashMap<String,SortedSet<Version>>();

            for (Builder builder : builders) {
                Jar jar = null;
                try {
                    jar = builder.build();
                    String exportHeader = jar.getManifest().getMainAttributes().getValue(Constants.EXPORT_PACKAGE);
                    if (exportHeader != null) {
                        Parameters parameters = new Parameters(exportHeader);
                        for (Entry<String,Attrs> export : parameters.entrySet()) {
                            Version version;
                            String versionStr = export.getValue().get(Constants.VERSION_ATTRIBUTE);
                            try {
                                version = Version.parseVersion(versionStr);
                                String pkgName = Processor.removeDuplicateMarker(export.getKey());
                                SortedSet<Version> versions = allExports.get(pkgName);
                                if (versions == null) {
                                    versions = new TreeSet<Version>();
                                    allExports.put(pkgName, versions);
                                }
                                versions.add(version);
                            } catch (IllegalArgumentException e) {
                                // Seems to be an invalid export, ignore it...
                            }
                        }
                    }
                } catch (Exception e) {
                    Plugin.getDefault().getLogger().logWarning(MessageFormat.format("Unable to process exported packages for builder of {0}.", builder.getPropertiesFile()), e);
                } finally {
                    if (jar != null)
                        jar.close();
                }
            }
            Central.setExportedPackageModel(project, allExports);

            Display display = PlatformUI.getWorkbench().getDisplay();
            SWTConcurrencyUtil.execForDisplay(display, true, new Runnable() {
                public void run() {
                    PlatformUI.getWorkbench().getDecoratorManager().update("bndtools.exportedPackageDecorator");
                }
            });

        } catch (Exception e) {
            logger.logWarning("Error persisting exported package model.", e);
        }

        return Status.OK_STATUS;
    }
}

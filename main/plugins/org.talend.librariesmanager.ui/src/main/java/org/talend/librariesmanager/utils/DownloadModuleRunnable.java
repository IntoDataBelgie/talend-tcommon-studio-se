// ============================================================================
//
// Copyright (C) 2006-2021 Talend Inc. - www.talend.com
//
// This source code is available under agreement available at
// %InstallDIR%\features\org.talend.rcp.branding.%PRODUCTNAME%\%PRODUCTNAME%license.txt
//
// You should have received a copy of the agreement
// along with this program; if not, write to Talend SA
// 9 rue Pages 92150 Suresnes, France
//
// ============================================================================
package org.talend.librariesmanager.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.SubMonitor;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.PlatformUI;
import org.talend.commons.exception.ExceptionHandler;
import org.talend.commons.ui.runtime.exception.MessageBoxExceptionHandler;
import org.talend.core.GlobalServiceRegister;
import org.talend.core.model.general.ILibrariesService;
import org.talend.core.model.general.ModuleToInstall;
import org.talend.librariesmanager.librarydata.LibraryDataService;
import org.talend.librariesmanager.ui.LibManagerUiPlugin;
import org.talend.librariesmanager.ui.i18n.Messages;
import org.talend.librariesmanager.ui.wizards.AcceptModuleLicensesWizard;
import org.talend.librariesmanager.ui.wizards.AcceptModuleLicensesWizardDialog;
import org.talend.librariesmanager.utils.nexus.ArtifactDownloadManager;

abstract public class DownloadModuleRunnable implements IRunnableWithProgress {

    protected List<ModuleToInstall> toDownload;

    protected Set<String> downloadFailed;

    protected Set<String> installedModules;

    private boolean checkLibraries;

    private boolean showErrorInDialog = true;

    private static final String DISABLE_LICENSE_ACCEPT = "disableLicenseAccept";

    private static boolean disableLicenseAcceptFlag = false;

    static {
        disableLicenseAcceptFlag = Boolean.valueOf(System.getProperty(DISABLE_LICENSE_ACCEPT, "false"));
    }

    /**
     * DOC sgandon DownloadModuleRunnable constructor comment.
     *
     * @param shell, never null, used to ask the user to accept the licenses
     * @param toDownload
     */
    public DownloadModuleRunnable(List<ModuleToInstall> toDownload) {
        this.toDownload = toDownload;
        downloadFailed = new HashSet<String>();
        installedModules = new HashSet<String>();
        checkLibraries = true;
    }

    public DownloadModuleRunnable(List<ModuleToInstall> toDownload, boolean checkLibraries) {
        this(toDownload);
        this.checkLibraries = checkLibraries;
    }

    @Override
    public void run(final IProgressMonitor monitor) {
        SubMonitor subMonitor = SubMonitor.convert(monitor,
                Messages.getString("ExternalModulesInstallDialog.downloading2") + " (" + toDownload.size() + ")", //$NON-NLS-1$
                toDownload.size() * 10 + 5);
        if (checkAndAcceptLicenses(subMonitor)) {
            downLoad(subMonitor);
        }
        if (monitor != null) {
            monitor.setCanceled(subMonitor.isCanceled());
            monitor.done();
        }
    }

    private void downLoad(final IProgressMonitor monitor) {
        List<ModuleToInstall> canBeDownloadList = new ArrayList<ModuleToInstall>();
        for (final ModuleToInstall module : toDownload) {
            boolean isLicenseAccepted = module.isFromCustomNexus()
                    || (LibManagerUiPlugin.getDefault().getPreferenceStore().contains(module.getLicenseType())
                            && LibManagerUiPlugin.getDefault().getPreferenceStore().getBoolean(module.getLicenseType())
                            || disableLicenseAcceptFlag);
            if (isLicenseAccepted) {
                canBeDownloadList.add(module);
            }
        }
        if (monitor != null && monitor.isCanceled()) {
            return;
        }
        ArtifactDownloadManager downloadManager = new ArtifactDownloadManager(canBeDownloadList, monitor);
        downloadManager.start();
        List<ModuleToInstall> finishedList = downloadManager.getDownloadFinishedList();
        for (ModuleToInstall module : finishedList) {
            installedModules.add(module.getName());
        }
        Map<ModuleToInstall, Exception> failedMap = downloadManager.getDownloadFailedMap();
        for (ModuleToInstall module : failedMap.keySet()) {
            downloadFailed.add(module.getName());
            LibraryDataService.getInstance().setJarMissing(module.getMavenUri());
            Exception ex = new Exception("Download " + module.getName() + " : " + module.getMavenUri() + " failed!",
                    failedMap.get(module));
            ExceptionHandler.process(ex);
        }
        if (showErrorInDialog && !downloadFailed.isEmpty()) {
            Exception ex = new Exception(Messages.getString("DownloadModuleRunnable.jar.download.failed",
                    Arrays.toString(downloadFailed.toArray(new String[downloadFailed.size()]))));
            MessageBoxExceptionHandler.process(ex);
        }

        if (checkLibraries) {
            ILibrariesService librariesService = (ILibrariesService) GlobalServiceRegister.getDefault()
                    .getService(ILibrariesService.class);
            librariesService.checkLibraries();
        }
    }

    protected boolean hasLicensesToAccept() {
        if (toDownload != null && toDownload.size() > 0 && !disableLicenseAcceptFlag) {
            for (ModuleToInstall module : toDownload) {
                // no need accept license if it is from custom nexus
                if (module.isFromCustomNexus()) {
                    continue;
                }
                String licenseType = module.getLicenseType();
                if (licenseType != null) {
                    boolean isLicenseAccepted = LibManagerUiPlugin.getDefault().getPreferenceStore()
                            .getBoolean(module.getLicenseType());
                    if (!isLicenseAccepted) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    protected boolean checkAndAcceptLicenses(final IProgressMonitor monitor) {
        final AtomicBoolean accepted = new AtomicBoolean(true);
        if (hasLicensesToAccept()) {
            Display.getDefault().syncExec(new Runnable() {

                @Override
                public void run() {
                    AcceptModuleLicensesWizard licensesWizard = new AcceptModuleLicensesWizard(toDownload);
                    AcceptModuleLicensesWizardDialog wizardDialog = new AcceptModuleLicensesWizardDialog(
                    		getShell(), licensesWizard, toDownload, monitor);
                    wizardDialog.setPageSize(700, 380);
                    wizardDialog.create();
                    if (wizardDialog.open() != Window.OK) {
                        accepted.set(false);
                    }
                }
            });

        }

        return accepted.get();
    }


	protected abstract Shell getShell();
	/**
     * DOC sgandon Comment method "acceptLicence".
     *
     * @param module
     */
    abstract protected boolean acceptLicence(ModuleToInstall module);

    /**
     * Getter for downloadFailed.
     *
     * @return the downloadFailed
     */
    public Set<String> getDownloadFailed() {
        return this.downloadFailed;
    }

    /**
     * Getter for installedModules.
     *
     * @return the installedModules
     */
    public Set<String> getInstalledModules() {
        return this.installedModules;
    }

    public void setShowErrorInDialog(boolean showErrorInDialog) {
        this.showErrorInDialog = showErrorInDialog;
    }

}

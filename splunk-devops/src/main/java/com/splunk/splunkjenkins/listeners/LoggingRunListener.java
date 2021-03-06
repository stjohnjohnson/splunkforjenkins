package com.splunk.splunkjenkins.listeners;


import com.splunk.splunkjenkins.Constants;
import com.splunk.splunkjenkins.LoggingJobExtractor;
import com.splunk.splunkjenkins.SplunkJenkinsInstallation;
import com.splunk.splunkjenkins.UserActionDSL;
import com.splunk.splunkjenkins.utils.SplunkLogService;
import com.splunk.splunkjenkins.utils.TestCaseResultUtils;
import hudson.EnvVars;
import hudson.Extension;
import hudson.Util;
import hudson.model.*;
import hudson.model.listeners.RunListener;
import hudson.scm.ChangeLogSet;
import hudson.scm.SCM;
import jenkins.model.CauseOfInterruption;
import jenkins.model.InterruptedBuildAction;
import org.apache.commons.lang.StringUtils;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import static com.splunk.splunkjenkins.Constants.BUILD_REPORT_ENV_TAG;
import static com.splunk.splunkjenkins.Constants.JOB_RESULT;
import static com.splunk.splunkjenkins.model.EventType.BUILD_EVENT;
import static com.splunk.splunkjenkins.utils.LogEventHelper.*;

@SuppressWarnings("unused")
@Extension
public class LoggingRunListener extends RunListener<Run> {
    private static final Logger LOG = Logger.getLogger(LoggingRunListener.class.getName());
    private final String NODE_NAME_KEY = "node";

    UserActionDSL postJobAction = new UserActionDSL();

    @Override
    public void onStarted(Run run, TaskListener listener) {
        if (SplunkJenkinsInstallation.get().isEventDisabled(BUILD_EVENT)) {
            return;
        }
        Map<String, Object> event = getCommonBuildInfo(run, false);
        event.put("type", "started");
        SplunkLogService.getInstance().send(event, BUILD_EVENT);
        //audit the start action
        if (event.get(Constants.USER_NAME_KEY) != null) {
            logUserAction((String) event.get(Constants.USER_NAME_KEY), Messages.audit_start_job(event.get(Constants.BUILD_ID)));
        }
        updateSlaveInfoAsync((String) event.get(NODE_NAME_KEY));
    }

    @Override
    public void onCompleted(Run run, @Nonnull TaskListener listener) {
        if (SplunkJenkinsInstallation.get().isEventDisabled(BUILD_EVENT)) {
            return;
        }
        Map<String, Object> event = getCommonBuildInfo(run, true);
        event.put("type", "completed");
        float duration = run.getDuration() / 1000f;
        if (duration < 0.01f) {
            //workflow job duration is updated after job completed
            //not available in onCompleted listener
            duration = Math.max(0, (System.currentTimeMillis() - run.getStartTimeInMillis()) / 100f);
        }
        event.put("job_duration", duration);
        event.put(JOB_RESULT, run.getResult().toString());
        Map testSummary = TestCaseResultUtils.getSummary(run);
        if (!testSummary.isEmpty()) {
            event.put("test_summary", testSummary);
        }
        if (run instanceof AbstractBuild) {
            AbstractBuild build = (AbstractBuild) run;
            List<String> changelog = getChangeLog(build);

            if (!changelog.isEmpty()) {
                event.put("changelog", changelog);
            }
            event.putAll(getScmInfo(build));
        }
        SplunkLogService.getInstance().send(event, BUILD_EVENT);
        //custom event processing dsl
        postJobAction.perform(run, listener);

        if (run.getExecutor() != null) {
            //JdkSplunkLogHandler.LogHolder.getSlaveLog(run.getExecutor().getOwner());
            updateSlaveInfoAsync((String) event.get(NODE_NAME_KEY));
        }
        //remove cached values
        LoggingQueueListener.expire(run.getQueueId());
        recordAbortAction(run);
    }

    /**
     * @param run Jenkins job Run
     * @return the upstream job url
     */
    private String getUpStreamUrl(Run run) {
        for (CauseAction action : run.getActions(CauseAction.class)) {
            Cause.UpstreamCause upstreamCause = action.findCause(Cause.UpstreamCause.class);
            if (upstreamCause != null) {
                return upstreamCause.getUpstreamUrl() + upstreamCause.getUpstreamBuild() + "/";
            }
        }
        return "";
    }

    /**
     * @param run Jenkins job run
     * @return causes separated by comma
     */
    private String getBuildCauses(Run run) {
        Set<String> causes = new LinkedHashSet<>();
        for (CauseAction action : run.getActions(CauseAction.class)) {
            for (Cause cause : action.getCauses()) {
                causes.add(cause.getShortDescription());
            }
        }
        for (InterruptedBuildAction action : run.getActions(InterruptedBuildAction.class)) {
            for (CauseOfInterruption cause : action.getCauses()) {
                causes.add(cause.getShortDescription());
            }
        }
        return StringUtils.join(causes, ", ");
    }

    /**
     * @param build jenkins job build
     * @return scm information, we only support git,svn and p4
     */
    public static Map<String, Object> getScmInfo(AbstractBuild build) {
        Map<String, Object> event = new HashMap<>();
        if (build.getProject().getScm() != null) {
            SCM scm = build.getProject().getScm();
            try {
                EnvVars envVars = build.getEnvironment(TaskListener.NULL);
                String className = scm.getClass().getName();
                //not support GIT_URL_N or SVN_URL_n
                // scm can be found at https://wiki.jenkins-ci.org/display/JENKINS/Plugins
                switch (className) {
                    case "hudson.plugins.git.GitSCM":
                        event.put("scm", "git");
                        event.put("scm_url", getScmURL(envVars, "GIT_URL"));
                        event.put("branch", envVars.get("GIT_BRANCH"));
                        event.put("revision", envVars.get("GIT_COMMIT"));
                        break;
                    case "hudson.scm.SubversionSCM":
                        event.put("scm", "svn");
                        event.put("scm_url", getScmURL(envVars, "SVN_URL"));
                        event.put("revision", envVars.get("SVN_REVISION"));
                        break;
                    case "org.jenkinsci.plugins.p4.PerforceScm":
                        event.put("scm", "p4");
                        event.put("p4_client", envVars.get("P4_CLIENT"));
                        event.put("revision", envVars.get("P4_CHANGELIST"));
                        break;
                    case "hudson.scm.NullSCM":
                        event.put("scm", "");
                        break;
                    default:
                        event.put("scm", className);
                }
            } catch (InterruptedException e) {
                LOG.log(Level.SEVERE, "InterruptedException failed to extract scm info", e);
            } catch (IOException e) {
                LOG.log(Level.SEVERE, "IOException failed to extract scm info", e);
            }
        }
        return event;
    }

    /**
     * @param envVars environment variables
     * @param prefix  scm prefix, such as GIT_URL, SVN_URL
     * @return parsed scm urls from build env, e.g. GIT_URL_1, GIT_URL_2, ... GIT_URL_10 or GIT_URL
     */
    public static String getScmURL(EnvVars envVars, String prefix) {
        String value = envVars.get(prefix);
        if (value == null) {
            List<String> urls = new ArrayList<>();
            //just probe max 10 url
            for (int i = 0; i < 10; i++) {
                String probe_url = envVars.get(prefix + "_" + i);
                if (probe_url != null) {
                    urls.add(probe_url);
                } else {
                    break;
                }
            }
            if (!urls.isEmpty()) {
                value = StringUtils.join(urls, ",");
            }
        }
        return value;
    }

    /**
     * @param run Jenkins build run
     * @return Build event which are common both to start/complete event
     * should not reference some fields only available after build such as result or duration
     */
    private Map<String, Object> getCommonBuildInfo(Run run, boolean completed) {
        Map<String, Object> event = new HashMap();
        event.put(Constants.TAG, Constants.JOB_EVENT_TAG_NAME);
        event.put("build_number", run.getNumber());
        event.put("trigger_by", getBuildCauses(run));
        event.put(Constants.USER_NAME_KEY, getTriggerUserName(run));
        long queueId = run.getQueueId();
        Float queueTime = LoggingQueueListener.getQueueTime(queueId);
        if (queueTime == null) {
            //the queue has been garbage collected
            queueTime = 0f;
        }
        event.put("queue_time", queueTime);
        event.put("queue_id", queueId);
        event.put(Constants.BUILD_ID, run.getUrl());
        event.put("upstream", getUpStreamUrl(run));
        event.put("job_started_at", run.getTimestampString2());
        event.put("job_name", run.getParent().getUrl());
        Map parameters = getBuildVariables(run);
        if (!parameters.isEmpty()) {
            event.put(BUILD_REPORT_ENV_TAG, parameters);
        }
        if (run.getParent() instanceof Describable) {
            String jobType = ((Describable) run.getParent()).getDescriptor().getDisplayName();
            event.put("job_type", jobType);
        }
        Executor executor = run.getExecutor();
        String nodeName = null;
        if (executor != null) {
            nodeName = executor.getOwner().getName();
            if (StringUtils.isEmpty(nodeName)) {
                nodeName = Constants.MASTER;
            }
        }
        event.put(NODE_NAME_KEY, nodeName);
        for (LoggingJobExtractor extendListener : LoggingJobExtractor.all()) {
            if (extendListener.targetType.isInstance(run)) {
                try {
                    Map<String, Object> extend = extendListener.extract(run, completed);
                    if (extend != null && !extend.isEmpty()) {
                        event.putAll(extend);
                    }
                } catch (Exception e) {
                    LOG.log(Level.SEVERE, "failed to extract job info", e);
                }
            }
        }
        return event;
    }

    /**
     * Send audit information
     *
     * @param run Jenkins job run
     */
    private void recordAbortAction(Run run) {
        List<InterruptedBuildAction> actions = run.getActions(InterruptedBuildAction.class);
        for (InterruptedBuildAction action : actions) {
            List<CauseOfInterruption.UserInterruption> interrupts = Util.filter(action.getCauses(), CauseOfInterruption.UserInterruption.class);
            if (!interrupts.isEmpty()) { //contains at most one record
                User user = interrupts.get(0).getUser();
                if (user != null) {
                    logUserAction(user.getFullName(), Messages.audit_abort_job(run.getUrl()));
                    break;
                }
            }
        }

    }

    /**
     * @param build Jenkins job build
     * @return scm change log
     */
    private List<String> getChangeLog(AbstractBuild build) {
        //check changelog
        List<String> changelog = new ArrayList<>();
        if (build.hasChangeSetComputed()) {
            ChangeLogSet<? extends ChangeLogSet.Entry> changeset = build.getChangeSet();
            for (ChangeLogSet.Entry entry : changeset) {
                StringBuilder sbr = new StringBuilder();
                sbr.append(entry.getTimestamp());
                sbr.append(SEPARATOR).append("commit:").append(entry.getCommitId());
                sbr.append(SEPARATOR).append("author:").append(entry.getAuthor());
                sbr.append(SEPARATOR).append("message:").append(entry.getMsg());
                changelog.add(sbr.toString());
            }
        }
        return changelog;
    }
}

/*
 * (c) Kitodo. Key to digital objects e. V. <contact@kitodo.org>
 *
 * This file is part of the Kitodo project.
 *
 * It is licensed under GNU General Public License version 3 or later.
 *
 * For the full copyright and license information, please read the
 * GPL3-License.txt file that was distributed with this source code.
 */

package org.kitodo.services.workflow;

import de.sub.goobi.config.ConfigCore;
import de.sub.goobi.forms.LoginForm;
import de.sub.goobi.helper.Helper;
import de.sub.goobi.helper.WebDav;
import de.sub.goobi.helper.tasks.TaskManager;
import de.sub.goobi.metadaten.MetadatenImagesHelper;

import java.io.IOException;
import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Objects;

import javax.naming.AuthenticationException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.goobi.production.cli.helper.WikiFieldHelper;
import org.goobi.production.flow.jobs.HistoryAnalyserJob;
import org.kitodo.data.database.beans.History;
import org.kitodo.data.database.beans.Process;
import org.kitodo.data.database.beans.Property;
import org.kitodo.data.database.beans.Task;
import org.kitodo.data.database.beans.User;
import org.kitodo.data.database.exceptions.DAOException;
import org.kitodo.data.database.helper.enums.HistoryTypeEnum;
import org.kitodo.data.database.helper.enums.PropertyType;
import org.kitodo.data.database.helper.enums.TaskEditType;
import org.kitodo.data.database.helper.enums.TaskStatus;
import org.kitodo.data.exceptions.DataException;
import org.kitodo.production.thread.TaskScriptThread;
import org.kitodo.services.ServiceManager;

public class WorkflowService {

    private int openTasksWithTheSameOrdering;
    private List<Task> automaticTasks;
    private List<Task> tasksToFinish;
    private Integer problemId;
    private Integer solutionId;
    private String problemMessage;
    private String solutionMessage;
    private final SimpleDateFormat formatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
    private static WorkflowService instance = null;
    private transient ServiceManager serviceManager = new ServiceManager();
    private static final Logger logger = LogManager.getLogger(WorkflowService.class);

    /**
     * Return singleton variable of type TaskService.
     *
     * @return unique instance of TaskService
     */
    public static WorkflowService getInstance() {
        if (Objects.equals(instance, null)) {
            synchronized (WorkflowService.class) {
                if (Objects.equals(instance, null)) {
                    instance = new WorkflowService();
                }
            }
        }
        return instance;
    }

    public Integer getProblemId() {
        return problemId;
    }

    public void setProblemId(Integer problemId) {
        this.problemId = problemId;
    }

    public Integer getSolutionId() {
        return solutionId;
    }

    public void setSolutionId(Integer solutionId) {
        this.solutionId = solutionId;
    }

    public String getProblemMessage() {
        return problemMessage;
    }

    public void setProblemMessage(String problemMessage) {
        this.problemMessage = problemMessage;
    }

    public String getSolutionMessage() {
        return solutionMessage;
    }

    public void setSolutionMessage(String solutionMessage) {
        this.solutionMessage = solutionMessage;
    }

    /**
     * Set Task status up.
     *
     * @param task
     *            to change status up
     * @return updated task
     */
    public Task setTaskStatusUp(Task task) throws DataException, IOException {
        if (task.getProcessingStatusEnum() != TaskStatus.DONE) {
            task = serviceManager.getTaskService().setProcessingStatusUp(task);
            task.setEditTypeEnum(TaskEditType.ADMIN);
            if (task.getProcessingStatusEnum() == TaskStatus.DONE) {
                close(task);
            } else {
                task.setProcessingTime(new Date());
                User user = (User) Helper.getManagedBeanValue("#{LoginForm.myBenutzer}");
                if (user != null) {
                    task.setProcessingUser(user);
                }
            }
        }
        return task;
    }

    /**
     * Change Task status down.
     *
     * @param task
     *            to change status down
     * @return updated task
     */
    public Task setTaskStatusDown(Task task) {
        task.setEditTypeEnum(TaskEditType.ADMIN);
        task.setProcessingTime(new Date());
        User user = (User) Helper.getManagedBeanValue("#{LoginForm.myBenutzer}");
        if (user != null) {
            task.setProcessingUser(user);
        }
        task = serviceManager.getTaskService().setProcessingStatusDown(task);
        return task;
    }

    /**
     * .
     * 
     * @param process
     *            object
     */
    public void setTasksStatusUp(Process process) throws DataException, IOException {
        List<Task> tasks = process.getTasks();

        for (Task task : tasks) {
            if (!task.getProcessingStatus().equals(TaskStatus.DONE.getValue())) {
                task.setProcessingStatus(task.getProcessingStatus() + 1);
                task.setEditType(TaskEditType.ADMIN.getValue());
                if (task.getProcessingStatus().equals(TaskStatus.DONE.getValue())) {
                    close(task);
                } else {
                    User user = (User) Helper.getManagedBeanValue("#{LoginForm.myBenutzer}");
                    if (user != null) {
                        task.setProcessingUser(user);
                        serviceManager.getTaskService().save(task);
                    }
                }
                break;
            }
        }
    }

    /**
     * .
     * 
     * @param process
     *            object
     */
    public void setTasksStatusDown(Process process) throws DataException {
        List<Task> tasks = new ArrayList<>(process.getTasks());
        Collections.reverse(tasks);

        for (Task task : tasks) {
            if (process.getTasks().get(0) != task && task.getProcessingStatusEnum() != TaskStatus.LOCKED) {
                task.setEditTypeEnum(TaskEditType.ADMIN);
                task.setProcessingTime(new Date());
                User user = (User) Helper.getManagedBeanValue("#{LoginForm.myBenutzer}");
                if (user != null) {
                    task.setProcessingUser(user);
                }
                task = serviceManager.getTaskService().setProcessingStatusDown(task);
                serviceManager.getTaskService().save(task);
                break;
            }
        }
    }

    /**
     * Not sure.
     *
     * @return closed Task
     */
    public Task closeTaskByUser(Task task, WebDav webDav) throws DataException, IOException {

        // if step allows writing of images, then count all images here
        if (task.isTypeImagesWrite()) {
            try {
                // this.mySchritt.getProzess().setSortHelperImages(
                // FileUtils.getNumberOfFiles(new
                // File(this.mySchritt.getProzess().getImagesOrigDirectory())));
                HistoryAnalyserJob.updateHistory(task.getProcess());
            } catch (Exception e) {
                Helper.setFehlerMeldung("Error while calculation of storage and images", e);
            }
        }

        /*
         * wenn das Resultat des Arbeitsschrittes zunÃ¤chst verifiziert werden soll,
         * dann ggf. das Abschliessen abbrechen
         */
        if (task.isTypeCloseVerify()) {
            /* Metadatenvalidierung */
            if (task.isTypeMetadata() && ConfigCore.getBooleanParameter("useMetadatenvalidierung")) {
                serviceManager.getMetadataValidationService().setAutoSave(true);
                if (!serviceManager.getMetadataValidationService().validate(task.getProcess())) {
                    return null;
                }
            }

            // image validation
            if (task.isTypeImagesWrite()) {
                MetadatenImagesHelper mih = new MetadatenImagesHelper(null, null);
                try {
                    if (!mih.checkIfImagesValid(task.getProcess().getTitle(),
                        serviceManager.getProcessService().getImagesOrigDirectory(false, task.getProcess()))) {
                        return null;
                    }
                } catch (Exception e) {
                    Helper.setFehlerMeldung("Error on image validation: ", e);
                }
            }
        }
        /*
         * wenn das Ergebnis der Verifizierung ok ist, dann weiter, ansonsten schon
         * vorher draussen
         */
        webDav.uploadFromHome(task.getProcess());
        task.setEditTypeEnum(TaskEditType.MANUAL_SINGLE);
        close(task);
        return task;
    }

    /**
     * Close task.
     *
     * @param task
     *            as Task object
     */
    public void close(Task task) throws DataException, IOException {
        task.setProcessingStatus(3);
        task.setProcessingTime(new Date());
        LoginForm loginForm = (LoginForm) Helper.getManagedBeanValue("#{LoginForm}");
        if (loginForm != null) {
            User user = loginForm.getMyBenutzer();
            if (user != null) {
                task.setProcessingUser(user);
            }
        }
        task.setProcessingEnd(new Date());

        serviceManager.getTaskService().save(task);
        automaticTasks = new ArrayList<>();
        tasksToFinish = new ArrayList<>();

        History history = new History(new Date(), task.getOrdering(), task.getTitle(), HistoryTypeEnum.taskDone,
                task.getProcess());
        serviceManager.getHistoryService().save(history);

        /*
         * prüfen, ob es Schritte gibt, die parallel stattfinden aber noch nicht
         * abgeschlossen sind
         */
        List<Task> tasks = task.getProcess().getTasks();
        List<Task> allHigherTasks = getAllHigherTasks(tasks, task);

        activateNextTask(allHigherTasks);

        Process po = task.getProcess();
        URI imagesOrigDirectory = serviceManager.getProcessService().getImagesOrigDirectory(true, po);
        Integer numberOfFiles = serviceManager.getFileService().getNumberOfFiles(imagesOrigDirectory);
        if (!po.getSortHelperImages().equals(numberOfFiles)) {
            po.setSortHelperImages(numberOfFiles);
            serviceManager.getProcessService().save(po);
        }

        updateProcessStatus(po);

        for (Task automaticTask : automaticTasks) {
            TaskScriptThread thread = new TaskScriptThread(automaticTask);
            TaskManager.addTask(thread);
        }
        for (Task finish : tasksToFinish) {
            close(finish);
        }
    }

    /**
     * Report the problem.
     *
     * @return Task
     */
    public Task reportProblem(Task task, WebDav webDav) {
        User user = (User) Helper.getManagedBeanValue("#{LoginForm.myBenutzer}");
        if (user == null) {
            Helper.setFehlerMeldung("userNotFound");
            return null;
        }
        if (logger.isDebugEnabled()) {
            logger.debug("mySchritt.ID: " + task.getId());
            logger.debug("Korrekturschritt.ID: " + this.problemId);
        }
        webDav.uploadFromHome(task.getProcess());
        Date date = new Date();
        task.setProcessingStatusEnum(TaskStatus.LOCKED);
        task.setEditTypeEnum(TaskEditType.MANUAL_SINGLE);
        task.setProcessingTime(date);
        task.setProcessingUser(user);
        task.setProcessingBegin(null);

        try {
            Task temp = serviceManager.getTaskService().getById(this.problemId);
            temp.setProcessingStatusEnum(TaskStatus.OPEN);
            temp = serviceManager.getTaskService().setCorrectionStep(temp);
            temp.setProcessingEnd(null);

            Property processProperty = new Property();
            processProperty.setTitle(Helper.getTranslation("Korrektur notwendig"));
            processProperty.setValue("[" + this.formatter.format(date) + ", "
                    + serviceManager.getUserService().getFullName(user) + "] " + this.problemMessage);
            processProperty.setType(PropertyType.messageError);
            processProperty.getProcesses().add(task.getProcess());
            task.getProcess().getProperties().add(processProperty);

            String message = Helper.getTranslation("KorrekturFuer") + " " + temp.getTitle() + ": " + this.problemMessage
                    + " (" + serviceManager.getUserService().getFullName(user) + ")";
            task.getProcess().setWikiField(
                WikiFieldHelper.getWikiMessage(task.getProcess(), task.getProcess().getWikiField(), "error", message));
            serviceManager.getTaskService().save(temp);
            task.getProcess().getHistory().add(new History(date, temp.getOrdering().doubleValue(), temp.getTitle(),
                    HistoryTypeEnum.taskError, temp.getProcess()));
            /*
             * alle Schritte zwischen dem aktuellen und dem Korrekturschritt wieder
             * schliessen
             */
            List<Task> allTasksInBetween = serviceManager.getTaskService().getAllTasksInBetween(temp.getOrdering(),
                task.getOrdering(), task.getProcess().getId());
            for (Task taskInBetween : allTasksInBetween) {
                taskInBetween.setProcessingStatusEnum(TaskStatus.LOCKED);
                taskInBetween = serviceManager.getTaskService().setCorrectionStep(taskInBetween);
                taskInBetween.setProcessingEnd(null);
                serviceManager.getTaskService().save(taskInBetween);
            }

            // den Prozess aktualisieren, so dass der Sortierungshelper gespeichert wird
            this.serviceManager.getProcessService().save(task.getProcess());
        } catch (DAOException | DataException e) {
            logger.error("Task couldn't get saved/inserted", e);
        }

        this.problemMessage = "";
        this.problemId = 0;
        return task;
    }

    /**
     * THis one is taken out of BatchStepHelper.
     *
     * @param currentTask
     *            Task
     * @param problemTask
     *            String
     */
    public Task reportProblem(Task currentTask, String problemTask) {
        Date date = new Date();
        currentTask.setProcessingStatusEnum(TaskStatus.LOCKED);
        currentTask.setEditTypeEnum(TaskEditType.MANUAL_SINGLE);
        currentTask.setProcessingTime(date);
        User ben = (User) Helper.getManagedBeanValue("#{LoginForm.myBenutzer}");
        if (ben != null) {
            currentTask.setProcessingUser(ben);
        }
        currentTask.setProcessingBegin(null);

        try {
            Task temp = null;
            for (Task task : currentTask.getProcess().getTasks()) {
                if (task.getTitle().equals(problemTask)) {
                    temp = task;
                }
            }
            if (temp != null) {
                temp.setProcessingStatusEnum(TaskStatus.OPEN);
                temp = serviceManager.getTaskService().setCorrectionStep(temp);
                temp.setProcessingEnd(null);

                Property processProperty = new Property();
                processProperty.setTitle(Helper.getTranslation("Korrektur notwendig"));
                processProperty.setValue("[" + this.formatter.format(date) + ", "
                        + serviceManager.getUserService().getFullName(ben) + "] " + this.problemMessage);
                processProperty.setType(PropertyType.messageError);
                processProperty.getProcesses().add(currentTask.getProcess());
                currentTask.getProcess().getProperties().add(processProperty);

                String message = Helper.getTranslation("KorrekturFuer") + " " + temp.getTitle() + ": "
                        + this.problemMessage + " (" + serviceManager.getUserService().getFullName(ben) + ")";
                currentTask.getProcess().setWikiField(WikiFieldHelper.getWikiMessage(currentTask.getProcess(),
                    currentTask.getProcess().getWikiField(), "error", message));

                this.serviceManager.getTaskService().save(temp);
                currentTask.getProcess().getHistory().add(new History(date, temp.getOrdering().doubleValue(),
                        temp.getTitle(), HistoryTypeEnum.taskError, temp.getProcess()));
                /*
                 * alle Schritte zwischen dem aktuellen und dem Korrekturschritt wieder
                 * schliessen
                 */
                List<Task> tasksInBetween = serviceManager.getTaskService().getAllTasksInBetween(
                    currentTask.getOrdering(), temp.getOrdering(), currentTask.getProcess().getId());
                for (Task task : tasksInBetween) {
                    task.setProcessingStatusEnum(TaskStatus.LOCKED);
                    task = serviceManager.getTaskService().setCorrectionStep(task);
                    task.setProcessingEnd(null);
                }
            }
            // den Prozess aktualisieren, so dass der Sortierungshelper gespeichert wird
        } catch (DataException e) {
            logger.error(e);
        }
        return currentTask;
    }

    /**
     * Solve problem. This one is taken from AktuelleSchritteForm
     *
     * @return Task
     */
    public Task solveProblem(Task task, WebDav webDav) {
        User user = (User) Helper.getManagedBeanValue("#{LoginForm.myBenutzer}");
        if (user == null) {
            Helper.setFehlerMeldung("userNotFound");
            return null;
        }
        Date now = new Date();
        webDav.uploadFromHome(task.getProcess());
        task.setProcessingStatusEnum(TaskStatus.DONE);
        task.setProcessingEnd(now);
        task.setEditTypeEnum(TaskEditType.MANUAL_SINGLE);
        task.setProcessingTime(new Date());
        task.setProcessingUser(user);

        try {
            Task temp = serviceManager.getTaskService().getById(this.solutionId);
            /*
             * alle Schritte zwischen dem aktuellen und dem Korrekturschritt wieder
             * schliessen
             */
            List<Task> allTasksInBetween = serviceManager.getTaskService().getAllTasksInBetween(temp.getOrdering(),
                task.getOrdering(), task.getProcess().getId());
            for (Task taskInBetween : allTasksInBetween) {
                taskInBetween.setProcessingStatusEnum(TaskStatus.DONE);
                taskInBetween.setProcessingEnd(now);
                taskInBetween.setPriority(0);
                if (taskInBetween.getId().intValue() == temp.getId().intValue()) {
                    taskInBetween.setProcessingStatusEnum(TaskStatus.OPEN);
                    taskInBetween = serviceManager.getTaskService().setCorrectionStep(taskInBetween);
                    // taskInBetween.setProcessingBegin(null);
                    taskInBetween.setProcessingEnd(null);
                    taskInBetween.setProcessingTime(now);
                }
                task.setProcessingTime(new Date());
                task.setProcessingUser(user);
                serviceManager.getTaskService().save(taskInBetween);
            }

            // den Prozess aktualisieren, so dass der Sortierungshelper gespeichert wird
            String message = Helper.getTranslation("KorrekturloesungFuer") + " " + temp.getTitle() + ": "
                    + this.solutionMessage + " (" + serviceManager.getUserService().getFullName(user) + ")";
            task.getProcess().setWikiField(
                WikiFieldHelper.getWikiMessage(task.getProcess(), task.getProcess().getWikiField(), "info", message));

            Property processProperty = new Property();
            processProperty.setTitle(Helper.getTranslation("Korrektur durchgefuehrt"));
            processProperty.setValue(
                "[" + this.formatter.format(new Date()) + ", " + serviceManager.getUserService().getFullName(user)
                        + "] " + Helper.getTranslation("KorrekturloesungFuer") + " " + temp.getTitle() + ": "
                        + this.solutionMessage);
            processProperty.setType(PropertyType.messageImportant);
            processProperty.getProcesses().add(task.getProcess());
            task.getProcess().getProperties().add(processProperty);
            serviceManager.getProcessService().save(task.getProcess());
        } catch (DAOException | DataException e) {
            logger.error("task couldn't get saved/inserted", e);
        }

        this.solutionMessage = "";
        this.solutionId = 0;
        return task;
    }

    /**
     * This one is taken out of BatchStepHelper.
     *
     * @param currentTask
     *            Task
     * @param solutionTask
     *            String
     * @param webDav
     *            WebDav
     */
    public Task solveProblem(Task currentTask, String solutionTask, WebDav webDav) throws AuthenticationException {
        User user = (User) Helper.getManagedBeanValue("#{LoginForm.myBenutzer}");
        if (user == null) {
            throw new AuthenticationException("userNotFound");
        }
        Date date = new Date();
        webDav.uploadFromHome(currentTask.getProcess());
        currentTask.setProcessingStatusEnum(TaskStatus.DONE);
        currentTask.setProcessingEnd(date);
        currentTask.setEditTypeEnum(TaskEditType.MANUAL_SINGLE);
        currentTask.setProcessingTime(date);
        currentTask.setProcessingUser(user);

        try {
            Task temp = null;
            for (Task task : currentTask.getProcess().getTasks()) {
                if (task.getTitle().equals(solutionTask)) {
                    temp = task;
                }
            }
            if (temp != null) {
                /*
                 * alle Schritte zwischen dem aktuellen und dem Korrekturschritt wieder
                 * schliessen
                 */
                List<Task> tasksInBetween = serviceManager.getTaskService().getAllTasksInBetween(temp.getOrdering(),
                    currentTask.getOrdering(), currentTask.getProcess().getId());
                for (Task task : tasksInBetween) {
                    task.setProcessingStatusEnum(TaskStatus.DONE);
                    task.setProcessingEnd(date);
                    task.setPriority(0);
                    if (task.getId().intValue() == temp.getId().intValue()) {
                        task.setProcessingStatusEnum(TaskStatus.OPEN);
                        task = serviceManager.getTaskService().setCorrectionStep(task);
                        task.setProcessingEnd(null);
                        task.setProcessingTime(date);
                    }
                    this.serviceManager.getTaskService().save(task);
                }

                Property processProperty = new Property();
                processProperty.setTitle(Helper.getTranslation("Korrektur durchgefuehrt"));
                processProperty.setValue(
                    "[" + this.formatter.format(new Date()) + ", " + serviceManager.getUserService().getFullName(user)
                            + "] " + Helper.getTranslation("KorrekturloesungFuer") + " " + temp.getTitle() + ": "
                            + this.solutionMessage);
                processProperty.getProcesses().add(currentTask.getProcess());
                processProperty.setType(PropertyType.messageImportant);
                currentTask.getProcess().getProperties().add(processProperty);

                String message = Helper.getTranslation("KorrekturloesungFuer") + " " + temp.getTitle() + ": "
                        + this.solutionMessage + " (" + serviceManager.getUserService().getFullName(user) + ")";
                currentTask.getProcess().setWikiField(WikiFieldHelper.getWikiMessage(currentTask.getProcess(),
                    currentTask.getProcess().getWikiField(), "info", message));
                // den Prozess aktualisieren, so dass der Sortierungshelper gespeichert wird
            }
        } catch (DataException e) {
            logger.error(e);
        }
        return currentTask;
    }

    private List<Task> getAllHigherTasks(List<Task> tasks, Task task) {
        List<Task> allHigherTasks = new ArrayList<>();
        this.openTasksWithTheSameOrdering = 0;
        for (Task tempTask : tasks) {
            if (tempTask.getOrdering().equals(task.getOrdering()) && tempTask.getProcessingStatus() != 3
                    && !tempTask.getId().equals(task.getId())) {
                openTasksWithTheSameOrdering++;
            } else if (tempTask.getOrdering() > task.getOrdering()) {
                allHigherTasks.add(tempTask);
            }
        }
        return allHigherTasks;
    }

    /**
     * If no open parallel tasks are available, activate the next tasks.
     */
    private void activateNextTask(List<Task> allHigherTasks) throws DataException {
        if (openTasksWithTheSameOrdering == 0) {
            int ordering = 0;
            boolean matched = false;
            for (Task task : allHigherTasks) {
                if (ordering < task.getOrdering() && !matched) {
                    ordering = task.getOrdering();
                }

                if (ordering == task.getOrdering() && task.getProcessingStatus() != 3
                        && task.getProcessingStatus() != 2) {
                    // den Schritt aktivieren, wenn es kein vollautomatischer ist
                    task.setProcessingStatus(1);
                    task.setProcessingTime(new Date());
                    task.setEditType(4);

                    History historyOpen = new History(new Date(), task.getOrdering(), task.getTitle(),
                            HistoryTypeEnum.taskOpen, task.getProcess());
                    serviceManager.getHistoryService().save(historyOpen);

                    // wenn es ein automatischer Schritt mit Script ist
                    if (task.isTypeAutomatic()) {
                        automaticTasks.add(task);
                    } else if (task.isTypeAcceptClose()) {
                        tasksToFinish.add(task);
                    }

                    serviceManager.getTaskService().save(task);
                    matched = true;
                } else {
                    if (matched) {
                        break;
                    }
                }
            }
        }
    }

    /**
     * Update process status.
     *
     * @param process
     *            the process
     */
    private void updateProcessStatus(Process process) throws DataException {
        String value = serviceManager.getProcessService().getProgress(process, null);
        process.setSortHelperStatus(value);
        serviceManager.getProcessService().save(process);
    }
}

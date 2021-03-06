package org.sunbird.learner.actors.bulkupload;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencsv.CSVWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.*;
import java.util.function.Function;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.sunbird.common.Constants;
import org.sunbird.common.exception.ProjectCommonException;
import org.sunbird.common.models.util.BulkUploadJsonKey;
import org.sunbird.common.models.util.JsonKey;
import org.sunbird.common.models.util.ProjectUtil;
import org.sunbird.common.request.Request;
import org.sunbird.common.request.RequestContext;
import org.sunbird.common.responsecode.ResponseCode;
import org.sunbird.common.util.CloudStorageUtil;
import org.sunbird.common.util.CloudStorageUtil.CloudStorageType;
import org.sunbird.learner.actors.bulkupload.dao.BulkUploadProcessDao;
import org.sunbird.learner.actors.bulkupload.dao.BulkUploadProcessTaskDao;
import org.sunbird.learner.actors.bulkupload.dao.impl.BulkUploadProcessDaoImpl;
import org.sunbird.learner.actors.bulkupload.dao.impl.BulkUploadProcessTaskDaoImpl;
import org.sunbird.learner.actors.bulkupload.model.BulkUploadProcess;
import org.sunbird.learner.actors.bulkupload.model.BulkUploadProcessTask;
import org.sunbird.learner.actors.bulkupload.model.StorageDetails;

public abstract class BaseBulkUploadBackgroundJobActor extends BaseBulkUploadActor {

  protected void setSuccessTaskStatus(
      BulkUploadProcessTask task,
      ProjectUtil.BulkProcessStatus status,
      Map<String, Object> row,
      String action)
      throws JsonProcessingException {
    ObjectMapper mapper = new ObjectMapper();
    row.put(JsonKey.OPERATION, action);
    task.setSuccessResult(mapper.writeValueAsString(row));
    task.setStatus(status.getValue());
  }

  protected void setTaskStatus(
      BulkUploadProcessTask task,
      ProjectUtil.BulkProcessStatus status,
      String failureMessage,
      Map<String, Object> row,
      String action)
      throws JsonProcessingException {
    ObjectMapper mapper = new ObjectMapper();
    row.put(JsonKey.OPERATION, action);
    if (ProjectUtil.BulkProcessStatus.COMPLETED.getValue() == status.getValue()) {
      task.setSuccessResult(mapper.writeValueAsString(row));
      task.setStatus(status.getValue());
    } else if (ProjectUtil.BulkProcessStatus.FAILED.getValue() == status.getValue()) {
      row.put(JsonKey.ERROR_MSG, failureMessage);
      task.setStatus(status.getValue());
      task.setFailureResult(mapper.writeValueAsString(row));
    }
  }

  public void handleBulkUploadBackground(Request request, Function function) {
    String processId = (String) request.get(JsonKey.PROCESS_ID);
    BulkUploadProcessDao bulkUploadDao = new BulkUploadProcessDaoImpl();
    String logMessagePrefix =
        MessageFormat.format(
            "BaseBulkUploadBackGroundJobActor:handleBulkUploadBackground:{0}: ", processId);

    logger.info(request.getRequestContext(), logMessagePrefix + "called");

    BulkUploadProcess bulkUploadProcess =
        bulkUploadDao.read(processId, request.getRequestContext());
    if (null == bulkUploadProcess) {
      logger.info(request.getRequestContext(), logMessagePrefix + "Invalid process ID.");
      return;
    }

    int status = bulkUploadProcess.getStatus();
    if (!(ProjectUtil.BulkProcessStatus.COMPLETED.getValue() == status)
        || ProjectUtil.BulkProcessStatus.INTERRUPT.getValue() == status) {
      try {
        function.apply(bulkUploadProcess);
      } catch (Exception e) {
        bulkUploadProcess.setStatus(ProjectUtil.BulkProcessStatus.FAILED.getValue());
        bulkUploadProcess.setFailureResult(e.getMessage());
        bulkUploadDao.update(bulkUploadProcess, null);
        logger.error(
            request.getRequestContext(),
            logMessagePrefix + "Exception occurred with error message = " + e.getMessage(),
            e);
      }
    }

    bulkUploadProcess.setStatus(ProjectUtil.BulkProcessStatus.COMPLETED.getValue());
    bulkUploadDao.update(bulkUploadProcess, request.getRequestContext());
  }

  public void processBulkUpload(
      BulkUploadProcess bulkUploadProcess,
      Function function,
      Map<String, String> outputColumnMap,
      String[] outputColumnsOrder,
      RequestContext context) {
    BulkUploadProcessTaskDao bulkUploadProcessTaskDao = new BulkUploadProcessTaskDaoImpl();
    String logMessagePrefix =
        MessageFormat.format(
            "BaseBulkUploadBackGroundJobActor:processBulkUpload:{0}: ", bulkUploadProcess.getId());
    Integer sequence = 0;
    Integer taskCount = bulkUploadProcess.getTaskCount();
    List<Map<String, Object>> successList = new LinkedList<>();
    List<Map<String, Object>> failureList = new LinkedList<>();
    while (sequence < taskCount) {
      Integer nextSequence = sequence + getBatchSize(JsonKey.CASSANDRA_WRITE_BATCH_SIZE);
      Map<String, Object> queryMap = new HashMap<>();
      queryMap.put(JsonKey.PROCESS_ID, bulkUploadProcess.getId());
      Map<String, Object> sequenceRange = new HashMap<>();
      sequenceRange.put(Constants.GT, sequence);
      sequenceRange.put(Constants.LTE, nextSequence);
      queryMap.put(BulkUploadJsonKey.SEQUENCE_ID, sequenceRange);
      List<BulkUploadProcessTask> tasks =
          bulkUploadProcessTaskDao.readByPrimaryKeys(queryMap, context);
      if (tasks == null) {
        logger.info(
            context,
            logMessagePrefix
                + "No bulkUploadProcessTask found for process id: "
                + bulkUploadProcess.getId()
                + " and range "
                + sequence
                + ":"
                + nextSequence);
        sequence = nextSequence;
        continue;
      }
      function.apply(tasks);

      try {
        ObjectMapper mapper = new ObjectMapper();
        for (BulkUploadProcessTask task : tasks) {

          if (task.getStatus().equals(ProjectUtil.BulkProcessStatus.FAILED.getValue())) {
            failureList.add(
                mapper.readValue(
                    task.getFailureResult(), new TypeReference<Map<String, Object>>() {}));
          } else if (task.getStatus().equals(ProjectUtil.BulkProcessStatus.COMPLETED.getValue())) {
            successList.add(
                mapper.readValue(
                    task.getSuccessResult(), new TypeReference<Map<String, Object>>() {}));
          }
        }

      } catch (IOException e) {
        logger.error(
            context,
            logMessagePrefix + "Exception occurred with error message = " + e.getMessage(),
            e);
      }
      performBatchUpdate(tasks, context);
      sequence = nextSequence;
    }
    setCompletionStatus(
        bulkUploadProcess, successList, failureList, outputColumnMap, outputColumnsOrder, context);
  }

  private void setCompletionStatus(
      BulkUploadProcess bulkUploadProcess,
      List successList,
      List failureList,
      Map<String, String> outputColumnsMap,
      String[] outputColumnsOrder,
      RequestContext context) {
    String logMessagePrefix =
        MessageFormat.format(
            "BaseBulkUploadBackGroundJobActor:processBulkUpload:{0}: ", bulkUploadProcess.getId());
    try {

      logger.info(context, logMessagePrefix + "completed");
      bulkUploadProcess.setSuccessResult(ProjectUtil.convertMapToJsonString(successList));
      bulkUploadProcess.setFailureResult(ProjectUtil.convertMapToJsonString(failureList));
      bulkUploadProcess.setStatus(ProjectUtil.BulkProcessStatus.COMPLETED.getValue());
      StorageDetails storageDetails =
          uploadResultToCloud(
              bulkUploadProcess,
              successList,
              failureList,
              outputColumnsMap,
              outputColumnsOrder,
              context);
      if (null != storageDetails) {
        bulkUploadProcess.setEncryptedStorageDetails(storageDetails);
      }
    } catch (Exception e) {
      logger.error(
          context,
          logMessagePrefix + "Exception occurred with error message := " + e.getMessage(),
          e);
    }
    BulkUploadProcessDao bulkUploadDao = new BulkUploadProcessDaoImpl();
    bulkUploadDao.update(bulkUploadProcess, context);
  }

  protected void validateMandatoryFields(
      Map<String, Object> csvColumns, BulkUploadProcessTask task, String[] mandatoryFields)
      throws JsonProcessingException {
    if (mandatoryFields != null) {
      for (String field : mandatoryFields) {
        if (StringUtils.isEmpty((String) csvColumns.get(field))) {
          String errorMessage =
              MessageFormat.format(
                  ResponseCode.mandatoryParamsMissing.getErrorMessage(), new Object[] {field});

          setTaskStatus(
              task, ProjectUtil.BulkProcessStatus.FAILED, errorMessage, csvColumns, JsonKey.CREATE);

          ProjectCommonException.throwClientErrorException(
              ResponseCode.mandatoryParamsMissing, errorMessage);
        }
      }
    }
  }

  private StorageDetails uploadResultToCloud(
      BulkUploadProcess bulkUploadProcess,
      List<Map<String, Object>> successList,
      List<Map<String, Object>> failureList,
      Map<String, String> outputColumnsMap,
      String[] outputColumnsOrder,
      RequestContext context)
      throws IOException {

    String objKey = generateObjectKey(bulkUploadProcess);
    File file = null;
    try {
      file = getFileHandle(bulkUploadProcess.getObjectType(), bulkUploadProcess.getId(), context);
      writeResultsToFile(file, successList, failureList, outputColumnsMap, outputColumnsOrder);
      CloudStorageUtil.upload(
          CloudStorageType.AZURE,
          bulkUploadProcess.getObjectType(),
          objKey,
          file.getAbsolutePath());
      return new StorageDetails(
          CloudStorageType.AZURE.getType(), bulkUploadProcess.getObjectType(), objKey);
    } catch (Exception ex) {
      logger.error(
          context, "Exception occurred while uploading file to cloud.:: " + ex.getMessage(), ex);
    } finally {
      FileUtils.deleteQuietly(file);
    }
    return null;
  }

  private File getFileHandle(String objType, String processId, RequestContext context) {
    String logMessagePrefix =
        MessageFormat.format("BaseBulkUploadBackGroundJobActor:getFileHandle:{0}: ", processId);
    File file = null;
    try {
      file = File.createTempFile(objType, "upload");
    } catch (IOException e) {
      logger.error(
          context,
          logMessagePrefix + "Exception occurred with error message = " + e.getMessage(),
          e);
    }
    return file;
  }

  private String generateObjectKey(BulkUploadProcess bulkUploadProcess) {
    String objType = bulkUploadProcess.getObjectType();
    String processId = bulkUploadProcess.getId();
    return "bulk_upload_" + objType + "_" + processId + ".csv";
  }

  private void writeResultsToFile(
      File file,
      List<Map<String, Object>> successList,
      List<Map<String, Object>> failureList,
      Map<String, String> outputColumnsMap,
      String[] outputColumnsOrder)
      throws IOException {
    try (CSVWriter csvWriter = new CSVWriter(new FileWriter(file))) {
      List<String> headerRowWithInternalNames = new ArrayList<>(Arrays.asList(outputColumnsOrder));

      headerRowWithInternalNames.add(JsonKey.BULK_UPLOAD_STATUS);
      headerRowWithInternalNames.add(JsonKey.BULK_UPLOAD_ERROR);

      if (MapUtils.isNotEmpty(outputColumnsMap)) {
        List<String> headerRowWithDisplayNames = new ArrayList<>();
        headerRowWithInternalNames.forEach(
            s -> {
              if (outputColumnsMap.containsKey(s)) {
                headerRowWithDisplayNames.add(outputColumnsMap.get(s));
              } else {
                headerRowWithDisplayNames.add(s);
              }
            });
        csvWriter.writeNext(headerRowWithDisplayNames.toArray(new String[0]));
      } else {
        csvWriter.writeNext(headerRowWithInternalNames.toArray(new String[0]));
      }

      addResults(successList, headerRowWithInternalNames, csvWriter);
      addResults(failureList, headerRowWithInternalNames, csvWriter);
      csvWriter.flush();
    }
  }

  private void addResults(
      List<Map<String, Object>> resultList, List<String> headerRows, CSVWriter csvWriter) {
    resultList
        .stream()
        .forEach(
            map -> {
              preProcessResult(map);
              String[] nextLine = new String[headerRows.size()];
              String errMsg = (String) map.get(JsonKey.ERROR_MSG);
              int i = 0;
              for (String field : headerRows) {
                if (JsonKey.BULK_UPLOAD_STATUS.equals(field)) {
                  nextLine[i++] = errMsg == null ? JsonKey.SUCCESS : JsonKey.FAILED;
                } else if (JsonKey.BULK_UPLOAD_ERROR.equals(field)) {
                  nextLine[i++] = errMsg == null ? "" : errMsg;
                } else {
                  nextLine[i++] = String.valueOf(map.get(field));
                }
              }
              csvWriter.writeNext(nextLine);
            });
  }

  public abstract void preProcessResult(Map<String, Object> result);
}

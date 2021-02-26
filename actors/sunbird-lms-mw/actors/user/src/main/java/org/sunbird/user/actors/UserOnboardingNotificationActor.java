package org.sunbird.user.actors;

import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.sunbird.actor.core.BaseActor;
import org.sunbird.actor.router.ActorConfig;
import org.sunbird.common.models.util.JsonKey;
import org.sunbird.common.request.Request;
import org.sunbird.common.util.KeycloakRequiredActionLinkUtil;
import org.sunbird.learner.util.UserUtility;
import org.sunbird.learner.util.Util;
import org.sunbird.services.sso.SSOManager;
import org.sunbird.services.sso.SSOServiceFactory;
import org.sunbird.user.util.UserActorOperations;

@ActorConfig(
  tasks = {},
  asyncTasks = {"processOnBoardingMailAndSms", "processPasswordResetMailAndSms"}
)
public class UserOnboardingNotificationActor extends BaseActor {

  @Override
  public void onReceive(Request request) throws Throwable {
    if (UserActorOperations.PROCESS_ONBOARDING_MAIL_AND_SMS
        .getValue()
        .equalsIgnoreCase(request.getOperation())) {
      sendEmailAndSms(request);
    } else {
      onReceiveUnsupportedOperation("ProcessOnBoardingMailAndSmsActor");
    }
  }

  private void sendEmailAndSms(Request request) {
    Map<String, Object> requestMap = request.getRequest();
    // generate required action link and shorten the url
    UserUtility.decryptUserData(requestMap);
    requestMap.put(JsonKey.USERNAME, requestMap.get(JsonKey.USERNAME));
    requestMap.put(JsonKey.REDIRECT_URI, Util.getSunbirdWebUrlPerTenent(requestMap));
    Util.getUserRequiredActionLink(requestMap, request.getRequestContext());
    if (request
        .getOperation()
        .equals(UserActorOperations.PROCESS_ONBOARDING_MAIL_AND_SMS.getValue())) {
      // user created successfully send the onboarding mail
      Request welcomeMailReqObj = Util.sendOnboardingMail(requestMap);
      welcomeMailReqObj.setRequestContext(request.getRequestContext());
      
       ObjectMapper mapper = new ObjectMapper();
      try {
        logger.info("Onbording Mail Object .......");
        logger.info(mapper.writeValueAsString(welcomeMailReqObj));
      } catch (JsonProcessingException e) {
        logger.error("Ex", e);
      }
      
      if (null != welcomeMailReqObj) {
        tellToAnother(welcomeMailReqObj);
      }
    } else if (request
        .getOperation()
        .equals(UserActorOperations.PROCESS_PASSWORD_RESET_MAIL_AND_SMS.getValue())) {
      Request resetMailReqObj = Util.sendResetPassMail(requestMap);
      resetMailReqObj.setRequestContext(request.getRequestContext());
      if (null != resetMailReqObj) {
        tellToAnother(resetMailReqObj);
      }
    }

    if (StringUtils.isNotBlank((String) requestMap.get(JsonKey.PHONE))) {
      Util.sendSMS(requestMap);
    }
    SSOManager ssoManager = SSOServiceFactory.getInstance();
    if (StringUtils.isBlank((String) requestMap.get(JsonKey.PASSWORD))) {
      ssoManager.setRequiredAction(
          (String) requestMap.get(JsonKey.USER_ID), KeycloakRequiredActionLinkUtil.UPDATE_PASSWORD);
    }

    if (StringUtils.isNotBlank((String) requestMap.get(JsonKey.EMAIL))) {
      ssoManager.setRequiredAction(
          (String) requestMap.get(JsonKey.USER_ID), KeycloakRequiredActionLinkUtil.VERIFY_EMAIL);
    }
  }
}

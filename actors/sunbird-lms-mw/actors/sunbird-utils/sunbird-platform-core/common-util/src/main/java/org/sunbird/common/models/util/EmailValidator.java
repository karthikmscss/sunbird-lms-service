package org.sunbird.common.models.util;

import org.apache.commons.lang.StringUtils;

/**
 * Helper class for validating email.
 *
 * @author Amit Kumar
 */
public class EmailValidator {

  private EmailValidator() {}

  /**
   * Validates format of email.
   *
   * @param email Email value.
   * @return True, if email format is valid. Otherwise, return false.
   */
  public static boolean isEmailValid(String email) {
    if (StringUtils.isBlank(email)) {
      return false;
    }
    return email.matches(PropertiesCache.getInstance().readProperty(JsonKey.SUNBIRD_EMAIL_REGEX));
  }
}

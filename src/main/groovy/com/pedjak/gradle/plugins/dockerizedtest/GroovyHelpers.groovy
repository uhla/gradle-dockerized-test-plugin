package com.pedjak.gradle.plugins.dockerizedtest

class GroovyHelpers {
  public static def getCurrentUserId = ["id", "-u"].execute().text.trim()
}

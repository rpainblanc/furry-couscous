import com.cloudbees.groovy.cps.NonCPS
import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic

import java.time.OffsetDateTime

def repository_owner
def repository_name
def debug = false
if (debug) {
    repository_owner = 'rpainblanc'
    repository_name = 'furry-couscous'
} else {
    repository_owner = 'dataiku'
    repository_name = 'dip'
}

@NonCPS
def getGitHubPRIssueEvents(String repository_owner, String repository_name, String github_token, def pr_number) {
    // Return the issues in the same order so that we can paginate and get all issues
    int page_size = 100
    String base_url = "https://api.github.com/repos/${repository_owner}/${repository_name}/issues/${pr_number}/events?sort=created&direction=asc&state=open&per_page=${page_size}"
    int page = 1
    def all_events = []
    while (page > 0) {
        def url = new URL("${base_url}&page=${page}")
        println("Opening URL ${url}")
        HttpURLConnection request = url.openConnection()
        request.setRequestProperty('User-Agent', 'jenkins@dataiku.com')
        request.setRequestProperty('Authorization', "token ${github_token}")
        request.setRequestProperty('Accept', 'application/vnd.github.v3+json')
        def events = new JsonSlurperClassic().parseText(request.content.text)
        int count = 0
        for (event in events) {
            all_events.add(event)
            count++
        }

        if (count == page_size) {
            page += 1
        } else {
            page = 0
        }
    }

    return all_events
}

@NonCPS
def getGitHubPRIssueTimelineEvents(String repository_owner, String repository_name, String github_token, def pr_number) {
    // Return the issues in the same order so that we can paginate and get all issues
    int page_size = 100
    String base_url = "https://api.github.com/repos/${repository_owner}/${repository_name}/issues/${pr_number}/timeline?per_page=${page_size}"
    int page = 1
    def all_events = []
    while (page > 0) {
        def url = new URL("${base_url}&page=${page}")
        println("Opening URL ${url}")
        HttpURLConnection request = url.openConnection()
        request.setRequestProperty('User-Agent', 'jenkins@dataiku.com')
        request.setRequestProperty('Authorization', "token ${github_token}")
        request.setRequestProperty('Accept', 'application/vnd.github.v3+json')
        def events = new JsonSlurperClassic().parseText(request.content.text)
        int count = 0
        for (event in events) {
            all_events.add(event)
            count++
        }

        if (count == page_size) {
            page += 1
        } else {
            page = 0
        }
    }

    return all_events
}

@NonCPS
def getGitHubPRWithLabels(String repository_owner, String repository_name, String github_token, def labels) {
    String labels_filter = ''
    if (labels != null) {
        labels_filter = labels.join(',')
    }
    // Return the issues in the same order so that we can paginate and get all issues
    int page_size = 100
    String base_url = "https://api.github.com/repos/${repository_owner}/${repository_name}/issues?sort=created&direction=asc&state=open&per_page=${page_size}&labels=${labels_filter}"
    int page = 1
    def all_issues = []
    while (page > 0) {
        def url = new URL("${base_url}&page=${page}")
        println("Opening URL ${url}")
        HttpURLConnection request = url.openConnection()
        request.setRequestProperty('User-Agent', 'jenkins@dataiku.com')
        request.setRequestProperty('Authorization', "token ${github_token}")
        request.setRequestProperty('Accept', 'application/vnd.github.v3+json')
        def issues = new JsonSlurperClassic().parseText(request.content.text)
        int count = 0
        for (issue in issues) {
            all_issues.add(issue)
            count++
        }

        if (count == page_size) {
            page += 1
        } else {
            page = 0
        }
    }

    return all_issues
}

def log_message(def messages, def message) {
    messages.add(message)
    println message
}

pipeline {
    agent {
        label 'built-in'
    }
    stages {
        stage('init') {
            steps {
                script {
                    sh 'git reset --hard && git clean -xfdf'
                    def commit_id = sh(returnStdout: true, script: 'git rev-parse --short HEAD').trim()
                    currentBuild.description = "Git hash: ${commit_id}"
                    def messages = []
                    withCredentials([
                            usernamePassword(credentialsId: 'github-access-for-jenkins-tests-token', usernameVariable: 'GITHUB_USER', passwordVariable: 'GITHUB_PASSWORD')]) {
                        try {
                            def github_prs = getGitHubPRWithLabels(repository_owner, repository_name, env.GITHUB_PASSWORD, ['integration-tests', 'build-ondemand'])
                            //def github_prs = getGitHubPRWithLabels(repository_owner, repository_name, env.GITHUB_PASSWORD, ['integration-tests'])
                            //def github_prs = readJSON file: 'data.json'
                            writeJSON file: 'github-prs.json', json: github_prs
                            for (pr in github_prs) {
                                def job_name = "dip-on-github-pr/PR-${pr.number}"
                                def job = Jenkins.get().getItemByFullName(job_name)
                                log_message(messages, "PR ${pr.number} (${pr.pull_request.url})")

                                if (!job) {
                                    // There is no Jenkins job yet for this PR
                                    log_message(messages, "There is no Jenkins job yet for this PR, waiting for the GitHub plugin to create one soon")
                                    continue
                                }
                                if (job.buildable && !job.inQueue && !job.building) {
                                    def last_build = job.lastCompletedBuild
                                    if (!last_build)  {
                                        // There is no build yet but the plugin should trigger one soon
                                        log_message(messages, "Jenkins job exists for this PR but no build was executed yet, waiting for the GitHub plugin to trigger one soon")
                                        // Set all non-serializable objects to null before entering Jenkins step
                                        job = null
                                        last_build = null
                                        continue
                                    }
                                    //job.scheduleBuild(0, new hudson.model.Cause.UserIdCause("jenkins"))
                                    def tm_events = getGitHubPRIssueTimelineEvents(repository_owner, repository_name, env.GITHUB_PASSWORD, pr.number)
                                    log_message(messages, "Jenkins job exists for this PR and last executed build is ${last_build.id} (${env.JENKINS_URL}${last_build.url}, startTime=${last_build.startTimeInMillis})")
                                    // Scan the timeline events received **after** the last build start time
                                    def tm_events_to_check = []
                                    for (event in tm_events) {
                                        def date
                                        if (event.event == 'committed') {
                                            date = event.committer.date
                                        } else if (event.event == 'labeled') {
                                            date = event.created_at
                                        } else if (event.event == 'commented') {
                                            date = event.created_at
                                        } else {
                                            // Not interesting event
                                            log_message(messages, "Event ${event.event} (${event.url}) is not relevant")
                                            continue
                                        }
                                        def date_millis = OffsetDateTime.parse(date).toEpochSecond() * 1000
                                        if (date_millis < last_build.startTimeInMillis) {
                                            // Event before last execution time    
                                            log_message(messages, "Event ${event.event} (${event.url}) is before last build")
                                            continue
                                        }
                                        log_message(messages, "Event ${event.event} (${event.url}) is after last build")
                                        tm_events_to_check.add(event)
                                    }

                                    def skip_pr = true
                                    for (event in tm_events_to_check) {
                                        if (event.event == 'committed') {
                                            // There is a commit more recent than the last execution time
                                            log_message(messages, "Commit ${event.sha} (${event.url}) is more recent than the last build, waiting for the GitHub plugin to trigger a new one soon")
                                            // No need to scan further
                                            break
                                        }
                                        
                                        if (event.event == 'labeled') {
                                            // Since we are already filtering PRs having the expected labels, we don't care which
                                            // label was added (this label may even have been removed since), just that any label was added
                                            log_message(messages, "Label ${event.label.name} (${event.url}) was added after the last build, need to trigger a new build explicitly")
                                            skip_pr = false
                                            continue
                                        }
                                        
                                        if (event.event == 'commented') {
                                            // TODO Check comment content is actually a builder template
                                            log_message(messages, "Comment ${event.id} (${event.url}) was added after the last build, need to trigger a new build explicitly")
                                            skip_pr = false
                                            continue
                                        }
                                    }
                                    job = null
                                    last_build = null
                                    writeJSON file: "PR-${pr.number}-tm-events.json", json: tm_events
                                    if (skip_pr) {
                                        println "Skip PR ${pr.number}"
                                    } else {
                                        println "Should trigger PR ${pr.number}"
                                    }
                                }
                            }
                        } finally {
                            writeJSON file: "messages.json", json: messages
                            archiveArtifacts artifacts: '*.json', allowEmptyArchive: true
                        }
                    }
                }
            }
        }
    }
}

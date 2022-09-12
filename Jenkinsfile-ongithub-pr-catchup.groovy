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
                    withCredentials([
                            usernamePassword(credentialsId: 'jenkins-dataiku', usernameVariable: 'GITHUB_USER', passwordVariable: 'GITHUB_PASSWORD')]) {
                        try {
                            def github_prs = getGitHubPRWithLabels(repository_owner, repository_name, env.GITHUB_PASSWORD, ['integration-tests', 'build-ondemand'])
                            writeJSON file: 'github-prs.json', json: github_prs
                            for (pr in github_prs) {
                                def job_name = "dip-on-github-pr/PR-${pr.number}"
                                def job = Jenkins.get().getItemByFullName(job_name)
                                println "====\r\nPR ${pr.number} (${pr.pull_request.url})\r\n===="

                                if (!job) {
                                    // There is no Jenkins job yet for this PR
                                    println "There is no Jenkins job yet for this PR, will be created by the GitHub plugin, skip PR"
                                    continue
                                }
                                if (!job.buildable) {
                                    println "Jenkins job is not buildable, skip PR"
                                    continue
                                } else if (job.inQueue) {
                                    println "Jenkins job is already in the queue, skip PR"
                                    continue
                                } else if (job.building) {
                                    println "Jenkins job is already building, skip PR"
                                    continue
                                } else {
                                    def last_build = job.lastCompletedBuild
                                    if (!last_build)  {
                                        // There is no build yet but the plugin should trigger one soon
                                        println "Jenkins job exists for this PR but no build was executed yet, will be executed by the GitHub plugin, skip PR"
                                        // Set all non-serializable objects to null before entering Jenkins step
                                        job = null
                                        last_build = null
                                        continue
                                    }
                                    println "Jenkins job is ${env.JENKINS_URL}${job_name}"
                                    def tm_events = getGitHubPRIssueTimelineEvents(repository_owner, repository_name, env.GITHUB_PASSWORD, pr.number)
                                    writeJSON file: "PR-${pr.number}-tm-events.json", json: tm_events
                                    println "Found a total of ${tm_events.length()} events in the timeline"

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
                                            continue
                                        }
                                        def date_millis = OffsetDateTime.parse(date).toEpochSecond() * 1000
                                        if (date_millis < last_build.startTimeInMillis) {
                                            // Event before last execution time    
                                            continue
                                        }
                                        tm_events_to_check.add(event)
                                    }
                                    println "After filtering on last build start time, found a total of ${tm_events_to_check.length()} events to check"

                                    def skip_pr = true
                                    for (event in tm_events_to_check) {
                                        if (event.event == 'committed') {
                                            // There is a commit more recent than the last execution time
                                            println "Commit ${event.sha} (${event.url}) was added after last build, waiting for the GitHub plugin to trigger a new one soon, skip scanning further events")
                                            // No need to scan further
                                            break
                                        }
                                        
                                        if (event.event == 'labeled') {
                                            // Since we are already filtering PRs having the expected labels, we don't care which
                                            // label was added (this label may even have been removed since), just that any label was added
                                            println "Label ${event.label.name} was added after last build, need to trigger a new build explicitly")
                                            skip_pr = false
                                            continue
                                        }
                                        
                                        if (event.event == 'commented') {
                                            // TODO Check comment content is actually a builder template
                                            println "Comment ${event.id} (${event.url}) was added after last build, need to trigger a new build explicitly")
                                            skip_pr = false
                                            continue
                                        }
                                    }
                                    job = null
                                    last_build = null
                                    if (skip_pr) {
                                        println "Nothing to do, skip PR"
                                    } else {
                                        println "Should trigger PR explicitly"
                                        //job.scheduleBuild(0, new hudson.model.Cause.UserIdCause("jenkins"))
                                    }
                                }
                            }
                        } finally {
                            archiveArtifacts artifacts: '*.json', allowEmptyArchive: true
                        }
                    }
                }
            }
        }
    }
}

import com.cloudbees.groovy.cps.NonCPS
import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic

import java.time.OffsetDateTime
import java.util.regex.Matcher
import java.util.regex.Pattern

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
                    withCredentials([
                            usernamePassword(credentialsId: 'jenkins-dataiku', usernameVariable: 'GITHUB_USER', passwordVariable: 'GITHUB_PASSWORD')]) {
                        try {
                            def github_prs = getGitHubPRWithLabels(repository_owner, repository_name, env.GITHUB_PASSWORD, ['integration-tests', 'build-ondemand'])
                            writeJSON file: 'github-prs.json', json: github_prs
                            def slack_prs = []
                            def slack_prs_messages = []
                            def banner_sep = '=================================================================================='
                            for (pr in github_prs) {
                                def job_name = "dip-on-github-pr/PR-${pr.number}"
                                def job_url = "${env.JENKINS_URL}job/dip-on-github-pr/job/PR-${pr.number}/"
                                def job = Jenkins.get().getItemByFullName(job_name)
                                println("${banner_sep}\nPR ${pr.number} (${pr.html_url})\n${banner_sep}")

                                if (!job) {
                                    // There is no Jenkins job yet for this PR
                                    println("There is no Jenkins job yet for this PR, will be created by the GitHub plugin, skip PR")
                                    continue
                                }
                                if (!job.buildable) {
                                    println("Jenkins job is not buildable, skip PR")
                                    // Set all non-serializable objects to null before moving on next step
                                    job = null
                                    continue
                                } else if (job.inQueue) {
                                    println("Jenkins job is already in the queue, skip PR")
                                    // Set all non-serializable objects to null before moving on next step
                                    job = null
                                    continue
                                } else if (job.building) {
                                    println("Jenkins job is already building, skip PR")
                                    // Set all non-serializable objects to null before moving on next step
                                    job = null
                                    continue
                                } else {
                                    def last_build = job.lastCompletedBuild
                                    if (!last_build)  {
                                        // There is no build yet but the plugin should trigger one soon
                                        println("Jenkins job exists for this PR but no build was executed yet, will be executed by the GitHub plugin, skip PR")
                                        // Set all non-serializable objects to null before moving on next step
                                        job = null
                                        last_build = null
                                        continue
                                    }
                                    def tm_events = getGitHubPRIssueTimelineEvents(repository_owner, repository_name, env.GITHUB_PASSWORD, pr.number)
                                    println("Last build for Jenkins job is ${job_url}${last_build.id}/: startTimeInMillis=${last_build.startTimeInMillis}")

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
                                            println("Ignore event '${event.event}'")
                                            continue
                                        }
                                        def date_millis = OffsetDateTime.parse(date).toEpochSecond() * 1000
                                        if (date_millis < last_build.startTimeInMillis) {
                                            // Event before last execution time    
                                            println("Ignore event '${event.event}', it is before last build execution time: ${date_millis} < ${last_build.startTimeInMillis}")
                                            continue
                                        }
                                        println("Retain to check event '${event.event}', it is after last build execution time: ${date_millis} > ${last_build.startTimeInMillis}")
                                        tm_events_to_check.add(event)
                                    }
                                    if (!tm_events_to_check) {
                                        println("There are no more events after last build execution time, skip PR")
                                        continue
                                    }

                                    def trigger_pr = false
                                    def trigger_with_github_plugin = false
                                    def reason = ""
                                    for (event in tm_events_to_check) {
                                        if (event.event == 'committed') {
                                            // There is a commit more recent than the last execution time
                                            reason += "• Commit '${event.sha}' was added, should be triggered by GitHub plugin.\n"
                                            trigger_with_github_plugin = true
                                            continue
                                        }
                                        
                                        if (event.event == 'labeled') {
                                            // Since we are already filtering PRs having the expected labels, we don't care which
                                            // label was added (this label may even have been removed since), just that any label was added
                                            reason += "• Label '${event.label.name}' was added.\n"
                                            trigger_pr = true
                                            continue
                                        }
                                        
                                        if (event.event == 'commented') {
                                            def matcher = Pattern.compile(/\A@jenkins-dataiku\s*\r\n(```|~~~)(json)?\r\n(?<BUILDERCONF>\{.+\})\r\n\s*\1.*\Z/, Pattern.DOTALL).matcher(event.body as String)
                                            if (matcher.matches()) {
                                                reason += "• Comment ${event.html_url} was added.\n"
                                                trigger_pr = true
                                            } else {
                                                // This is not a valid comment to trigger a build
                                                reason += "• Comment ${event.html_url} was added but does not contain a builder template.\n"
                                            }
                                            matcher = null
                                            continue
                                        }
                                    }
                                    // Set all non-serializable objects to null before moving on next step
                                    job = null
                                    last_build = null
                                    writeJSON file: "PR-${pr.number}-tm-events.json", json: tm_events
                                    if (trigger_with_github_plugin) {
                                        println("==> No need to trigger this PR explicitly because:\n${reason}")
                                    } else if (trigger_pr) {
                                        println("==> Should trigger job ${job_url} for this PR explicitly because:\n${reason}")
                                        slack_prs_messages.add("Job for PR ${pr.number} (${pr.html_url}) should be triggered explicitly because:\n${reason}\nLink to job is ${job_url}\n\n")
                                        slack_prs.add(pr)
                                        //job.scheduleBuild(0, new hudson.model.Cause.UserIdCause("jenkins"))
                                    } else {
                                        println("==> No need to trigger this PR explicitly because:\n${reason}")
                                    }
                                }
                            }
                            if (slack_prs) {
                                def description = "<div>There are ${slack_prs.size()} PRs waiting for execution:</div>"
                                for (pr in slack_prs) {
                                    description += "<div>• <a href=\"${pr.html_url}/\">PR-${pr.number}</a></div>"
                                }
                                currentBuild.description = description
                                println("${banner_sep}\n"+slack_prs_messages.join("${banner_sep}\n"))
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

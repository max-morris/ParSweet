# ParSweet Development
* When the existing Java and C++ implementations diverge, surface it and request a decision.
* When you want to make a change to existing functionality, surface it and request a decision.
* Any addition to the Java repo should have a counterpart in the C++ repo and vice-versa.
* The benchmarks should all be parameterized based on the lock type and should loop over all lock types.
* Each commit should be bite-sized, i.e. small enough for a human to reasonably review. Keep the diff < 500 lines unless there's a good reason not to.
* Generated code should be well-commented.
* Before each commit, spawn a harsh reviewer session. If the review finds problems. fix them and repeat this step.
* Maintain the coding style already present in the repo.
* Never leave an exception catch block empty. If you think it will never trigger, insert a print statement and make it a fatal error.

Feature: Lending

  Rule: A book is lent to one member at a time

    @minimum @allium-rule-success.LendBook
    @item-01-001
    Scenario: A member borrows a book
      Given book B-1 is on the shelf
      When member M-1 borrows book B-1
      Then book B-1 is lent to member M-1

    @minimum @allium-rule-failure.LendBook.1
    @item-01-002
    Scenario: A book already lent is not lent again
      Given book B-1 is lent to member M-1
      When member M-2 borrows book B-1
      Then the loan is refused

  Rule: A book comes back whoever returns it

    @minimum @allium-rule-success.ReturnBook
    @item-01-003
    Scenario: A member returns a book
      Given book B-1 is lent to member M-1
      When member M-1 returns book B-1
      Then book B-1 is back on the shelf
